import argparse
import hashlib
import json
import os
import re
from pathlib import Path

from release_images import (
    ReleaseError,
    metadata,
    read_json,
    require,
    run,
    validate_manifest,
    write_json,
)
from release_migration import api
from release_rollout import context as rollout_context
from release_rollout import stable
from release_secrets import canonical_public_key

ROOT = Path(__file__).resolve().parents[1]
MODULE = ROOT / "infra/aws"
CONFIGURATION = frozenset(
    {
        "app_domain",
        "cloudfront_public_key",
        "embedding_model_arn",
        "analysis_model_id",
        "analysis_model_arns",
        "route53_zone_id",
        "budget_alert_emails",
        "vpc_cidr",
        "database_instance_class",
        "database_engine_version",
        "database_maximum_storage_gib",
        "log_retention_days",
        "maximum_service_tasks",
        "monthly_budget_usd",
    }
)
REQUIRED_CONFIGURATION = frozenset(
    {
        "app_domain",
        "cloudfront_public_key",
        "embedding_model_arn",
        "analysis_model_id",
        "analysis_model_arns",
        "route53_zone_id",
        "budget_alert_emails",
    }
)
PROTECTED_RESOURCES = frozenset(
    {
        "aws_db_instance",
        "aws_s3_bucket",
        "aws_cognito_user_pool",
        "aws_secretsmanager_secret",
        "aws_kms_key",
        "aws_cloudfront_public_key",
        "aws_ecs_service",
        "aws_sfn_state_machine",
    }
)
SCALING_RESOURCES = frozenset(
    {"aws_appautoscaling_target", "aws_appautoscaling_policy"}
)
SECRET_ENVIRONMENT = frozenset(
    {
        "DATABASE_PASSWORD",
        "APPLICATION_DATABASE_PASSWORD",
        "CLOUDFRONT_PRIVATE_KEY",
        "NEXTAUTH_SECRET",
    }
)


def redact(value, sensitive=False):
    if sensitive is True:
        return "[sensitive]"
    if isinstance(value, dict):
        return {
            key: redact(
                item,
                True
                if key
                in {
                    "password",
                    "master_password",
                    "secret_string",
                    "secret_binary",
                    "client_secret",
                    "private_key",
                }
                else sensitive.get(key, False)
                if isinstance(sensitive, dict)
                else False,
            )
            for key, item in value.items()
        }
    if isinstance(value, list):
        return [
            redact(
                item,
                sensitive[index]
                if isinstance(sensitive, list) and index < len(sensitive)
                else False,
            )
            for index, item in enumerate(value)
        ]
    return value


def review(plan):
    return [
        {
            "address": resource["address"],
            "type": resource["type"],
            "actions": resource["change"]["actions"],
            "before": redact(
                resource["change"].get("before"),
                resource["change"].get("before_sensitive", False),
            ),
            "after": redact(
                resource["change"].get("after"),
                resource["change"].get("after_sensitive", False),
            ),
        }
        for resource in plan.get("resource_changes", [])
        if resource["change"]["actions"] not in [["no-op"], ["read"]]
    ]


def parse_json(raw):
    try:
        parsed = json.loads(raw)
    except ValueError:
        raise ReleaseError("Terraform returned invalid release JSON.") from None
    require(isinstance(parsed, dict), "Terraform returned invalid release JSON.")
    return parsed


def terraform(*arguments):
    return run(
        [os.environ.get("TERRAFORM", "terraform"), f"-chdir={MODULE}", *arguments],
        timeout=3600,
    )


def configuration(release, manifest, supplied, *, scaling=False):
    validate_manifest(manifest, release)
    require(
        isinstance(supplied, dict)
        and REQUIRED_CONFIGURATION <= set(supplied) <= CONFIGURATION,
        "Provide only public Terraform configuration, including domain, models, signing public key, hosted zone, and budget recipients.",
    )
    public = supplied.get("cloudfront_public_key")
    require(
        isinstance(public, str)
        and public.strip().startswith("-----BEGIN PUBLIC KEY-----"),
        "Terraform accepts only a public CloudFront signing key.",
    )
    canonical_public_key(public)
    for key in ["analysis_model_arns", "budget_alert_emails"]:
        require(
            isinstance(supplied.get(key), list)
            and supplied[key]
            and all(isinstance(value, str) for value in supplied[key]),
            "Provide nonempty arrays of public model permissions and budget recipients.",
        )
    supplied = {
        **supplied,
        "analysis_model_arns": sorted(supplied["analysis_model_arns"]),
        "budget_alert_emails": sorted(supplied["budget_alert_emails"]),
    }
    return {
        **supplied,
        "application_name": release["application"],
        "environment": release["environment"],
        "region": release["region"],
        "image_digests": {
            key: image["imageDigest"] for key, image in manifest["images"].items()
        },
        "services_enabled": scaling,
    }


def verified_source(release):
    require(
        run(["git", "-C", str(ROOT), "rev-parse", "HEAD"]).strip()
        == release["sourceCommit"],
        "Infrastructure must come from this release's exact source commit.",
    )
    run(["git", "-C", str(ROOT), "diff", "--quiet"])
    run(["git", "-C", str(ROOT), "diff", "--cached", "--quiet"])
    require(
        not run(
            [
                "git",
                "-C",
                str(ROOT),
                "ls-files",
                "--others",
                "--exclude-standard",
                "infra/aws",
                ".github/workflows",
                "scripts/release_*",
            ]
        ).strip(),
        "Do not deploy untracked infrastructure or release code.",
    )


def initialize_backend(release):
    verified_source(release)
    require(
        api(release["region"], "sts", "get-caller-identity").get("Account")
        == release["accountId"],
        "Infrastructure credentials belong to another AWS account.",
    )
    bucket = f"{release['application']}-{release['environment']}-{release['accountId']}-state"
    terraform(
        "init",
        "-input=false",
        "-reconfigure",
        "-lockfile=readonly",
        f"-backend-config=bucket={bucket}",
        f"-backend-config=key={release['environment']}/closetos.tfstate",
        f"-backend-config=region={release['region']}",
        "-backend-config=encrypt=true",
        "-backend-config=use_lockfile=true",
    )
    terraform("fmt", "-check", "-recursive")
    terraform("validate", "-no-color")


def resources(module):
    require(isinstance(module, dict), "Terraform state has an invalid module.")
    found = module.get("resources", [])
    children = module.get("child_modules", [])
    require(
        isinstance(found, list)
        and all(isinstance(item, dict) for item in found)
        and isinstance(children, list),
        "Terraform state has invalid resources.",
    )
    return found + [resource for child in children for resource in resources(child)]


def scaling_enabled(state):
    values = state.get("values", {})
    require(isinstance(values, dict), "Terraform state values are invalid.")
    active = [
        item
        for item in resources(values.get("root_module", {}))
        if item.get("type") == "aws_appautoscaling_target"
        and item.get("mode") == "managed"
    ]
    require(
        len(active) in {0, 2},
        "Restore both service autoscaling targets before preparing another release.",
    )
    return bool(active)


def inspect_plan(plan, stage):
    require(
        plan.get("format_version") == "1.2"
        and isinstance(plan.get("terraform_version"), str)
        and re.fullmatch(r"1\.16\.[0-9]+", plan["terraform_version"]),
        "Use the repository's Terraform 1.16 release plan format.",
    )
    require(
        plan.get("errored") is not True and plan.get("complete") is True,
        "Infrastructure planning must complete before release approval.",
    )
    changes = plan.get("resource_changes", [])
    require(
        isinstance(changes, list), "Terraform did not report release resource changes."
    )
    summary = []
    for resource in changes:
        require(
            isinstance(resource, dict) and isinstance(resource.get("change"), dict),
            "A Terraform resource change is invalid.",
        )
        change, kind = resource["change"], resource.get("type")
        require(
            isinstance(resource.get("address"), str) and resource["address"],
            "Terraform must identify each planned resource address.",
        )
        actions = change.get("actions")
        require(
            isinstance(actions, list)
            and actions
            and all(
                action in {"no-op", "read", "create", "update", "delete"}
                for action in actions
            ),
            "Terraform reported an unsupported release action.",
        )
        require(
            kind != "aws_secretsmanager_secret_version",
            "Secret values must remain outside Terraform plans and state.",
        )
        before, after = change.get("before") or {}, change.get("after") or {}
        require(
            isinstance(before, dict) and isinstance(after, dict),
            "Terraform reported invalid resource values.",
        )
        if kind == "aws_ecs_task_definition":
            if "delete" in actions:
                require(
                    before.get("skip_destroy") is True
                    or str(before.get("family", "")).endswith("-migration"),
                    "Retain existing task definitions before planning their replacement for rollback.",
                )
            if "delete" not in actions or "create" in actions:
                require(
                    after.get("skip_destroy") is True
                    or str(after.get("family", "")).endswith("-migration"),
                    "Release task definitions must remain registered for rollback.",
                )
            for values in [before, after]:
                containers = values.get("container_definitions")
                if not containers:
                    continue
                try:
                    definitions = json.loads(containers)
                except (ValueError, TypeError):
                    raise ReleaseError(
                        "Terraform contains invalid task container definitions."
                    ) from None
                require(
                    isinstance(definitions, list)
                    and all(isinstance(container, dict) for container in definitions),
                    "Terraform contains invalid task containers.",
                )
                for container in definitions:
                    require(
                        not any(
                            item.get("name") in SECRET_ENVIRONMENT
                            for item in container.get("environment", [])
                        ),
                        "Container credential values must come from Secrets Manager references.",
                    )
        require(
            not (kind in PROTECTED_RESOURCES and "delete" in actions),
            "A release cannot destroy or replace protected application data, identity, signing keys, services, or workflows.",
        )
        if kind == "aws_ecs_service" and resource.get("mode") == "managed":
            require(
                (not before and after.get("desired_count") == 0)
                or (
                    before
                    and before.get("desired_count") == after.get("desired_count")
                    and before.get("task_definition") == after.get("task_definition")
                ),
                "Terraform preparation must preserve live service revisions and counts, and create only stopped services.",
            )
        if kind == "aws_sfn_state_machine" and before:
            require(
                before.get("definition") == after.get("definition"),
                "The worker workflow must stay unchanged until the migration and service rollout gates.",
            )
        if actions not in [["no-op"], ["read"]]:
            require(
                stage != "scaling" or kind in SCALING_RESOURCES,
                "Scaling activation cannot apply unrelated infrastructure changes.",
            )
            summary.append({"address": resource.get("address"), "actions": actions})
    return summary


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def verify_plan_variables(plan, variables):
    recorded = plan.get("variables")
    require(
        isinstance(recorded, dict)
        and all(
            isinstance(recorded.get(key), dict) and recorded[key].get("value") == value
            for key, value in variables.items()
        ),
        "The binary Terraform plan does not describe this exact release configuration.",
    )


def plan_release(release, manifest, supplied, destination):
    variables = configuration(release, manifest, supplied)
    destination = Path(destination).resolve()
    require(not destination.exists(), "Use a fresh infrastructure plan directory.")
    initialize_backend(release)
    variables["services_enabled"] = scaling_enabled(
        parse_json(terraform("show", "-json"))
    )
    destination.mkdir(parents=True, mode=0o700)
    variables_file, plan_file = (
        destination / "release.tfvars.json",
        destination / "prepare.tfplan",
    )
    write_json(variables_file, variables)
    terraform(
        "plan",
        "-input=false",
        "-lock-timeout=5m",
        "-no-color",
        f"-var-file={variables_file}",
        f"-out={plan_file}",
    )
    rendered = parse_json(terraform("show", "-json", str(plan_file)))
    verify_plan_variables(rendered, variables)
    summary = inspect_plan(rendered, "prepare")
    write_json(destination / "review.json", review(rendered))
    result = {
        **release,
        "status": "PLANNED",
        "planDigest": digest(plan_file),
        "variablesDigest": digest(variables_file),
        "reviewDigest": digest(destination / "review.json"),
        "changes": summary,
    }
    write_json(destination / "plan.json", result)
    return result


def verified_bundle(release, manifest, directory):
    directory = Path(directory).resolve()
    receipt = read_json(directory / "plan.json")
    require(
        isinstance(receipt, dict)
        and all(receipt.get(key) == value for key, value in release.items())
        and receipt.get("status") == "PLANNED",
        "The infrastructure plan belongs to another commit, publication, or environment.",
    )
    variables_file, plan_file = (
        directory / "release.tfvars.json",
        directory / "prepare.tfplan",
    )
    require(
        receipt.get("planDigest") == digest(plan_file)
        and receipt.get("variablesDigest") == digest(variables_file)
        and receipt.get("reviewDigest") == digest(directory / "review.json"),
        "The reviewed infrastructure plan or its variables changed after planning.",
    )
    variables = read_json(variables_file)
    require(
        isinstance(variables, dict) and type(variables.get("services_enabled")) is bool,
        "The release scaling configuration is invalid.",
    )
    supplied = {key: value for key, value in variables.items() if key in CONFIGURATION}
    require(
        variables
        == configuration(
            release, manifest, supplied, scaling=variables["services_enabled"]
        ),
        "Infrastructure plan variables do not match this exact immutable release.",
    )
    return directory, variables


def apply_release(release, manifest, directory, destination):
    directory, variables = verified_bundle(release, manifest, directory)
    require(
        not Path(destination).exists(), "Use a fresh infrastructure output destination."
    )
    initialize_backend(release)
    plan = parse_json(terraform("show", "-json", str(directory / "prepare.tfplan")))
    verify_plan_variables(plan, variables)
    inspect_plan(plan, "prepare")
    terraform(
        "apply",
        "-input=false",
        "-lock-timeout=5m",
        "-no-color",
        str(directory / "prepare.tfplan"),
    )
    outputs = parse_json(terraform("output", "-json"))
    rollout_context(outputs, release)
    require(
        all(
            isinstance(entry, dict) and entry.get("sensitive") is not True
            for entry in outputs.values()
        ),
        "Credential values must never be published as infrastructure outputs.",
    )
    write_json(destination, outputs)
    return outputs


def verify_live_release(release, target):
    response = api(
        release["region"],
        "ecs",
        "describe-services",
        "--cluster",
        target["cluster"],
        "--services",
        *target["services"].values(),
    )
    services = response.get("services")
    require(
        not response.get("failures")
        and isinstance(services, list)
        and len(services) == 2
        and all(isinstance(service, dict) for service in services)
        and {service.get("serviceName") for service in services}
        == set(target["services"].values()),
        "Reconfirm both release services before activating autoscaling.",
    )
    for service in services:
        key = next(
            key
            for key, name in target["services"].items()
            if name == service["serviceName"]
        )
        require(
            service.get("clusterArn") == target["cluster"]
            and service.get("status") == "ACTIVE"
            and all(
                type(service.get(count)) is int and service[count] >= 0
                for count in ["desiredCount", "runningCount", "pendingCount"]
            )
            and isinstance(service.get("deployments"), list)
            and all(isinstance(item, dict) for item in service["deployments"])
            and stable(service, target["definitions"][key]),
            "Autoscaling requires both expected task revisions to remain healthy.",
        )
    workflow = api(
        release["region"],
        "stepfunctions",
        "describe-state-machine",
        "--state-machine-arn",
        target["machine"],
    )
    require(
        workflow.get("stateMachineArn") == target["machine"]
        and workflow.get("status") == "ACTIVE"
        and parse_json(workflow.get("definition", "")) == target["workflow"],
        "Autoscaling requires the expected worker workflow to remain active.",
    )


def activate_scaling(release, manifest, directory, rollout_receipt, destination):
    _, variables = verified_bundle(release, manifest, directory)
    require(
        isinstance(rollout_receipt, dict)
        and all(rollout_receipt.get(key) == value for key, value in release.items())
        and rollout_receipt.get("status") == "SUCCEEDED",
        "Activate scaling only after this release's confirmed rollout and smoke checks.",
    )
    destination = Path(destination).resolve()
    require(not destination.exists(), "Use a fresh scaling activation directory.")
    initialize_backend(release)
    outputs = parse_json(terraform("output", "-json"))
    target = rollout_context(outputs, release)
    require(
        rollout_receipt.get("taskDefinitions") == target["definitions"]
        and rollout_receipt.get("clusterArn") == target["cluster"]
        and rollout_receipt.get("stateMachineArn") == target["machine"],
        "Scaling receipt does not identify this environment's prepared task revisions.",
    )
    verify_live_release(release, target)
    variables["services_enabled"] = True
    destination.mkdir(parents=True, mode=0o700)
    variables_file, plan_file = (
        destination / "release.tfvars.json",
        destination / "scaling.tfplan",
    )
    write_json(variables_file, variables)
    terraform(
        "plan",
        "-input=false",
        "-lock-timeout=5m",
        "-no-color",
        f"-var-file={variables_file}",
        f"-out={plan_file}",
    )
    rendered = parse_json(terraform("show", "-json", str(plan_file)))
    verify_plan_variables(rendered, variables)
    summary = inspect_plan(rendered, "scaling")
    terraform("apply", "-input=false", "-lock-timeout=5m", "-no-color", str(plan_file))
    require(
        scaling_enabled(parse_json(terraform("show", "-json"))),
        "Terraform did not confirm both service autoscaling targets.",
    )
    result = {**release, "status": "SUCCEEDED", "changes": summary}
    write_json(destination / "result.json", result)
    return result


def main():
    parser = argparse.ArgumentParser(
        description="Prepare a bound Terraform release plan, apply its reviewed artifact, and activate scaling after rollout."
    )
    parser.add_argument("action", choices=["check", "plan", "apply", "activate"])
    parser.add_argument("--account", required=True)
    parser.add_argument("--region", required=True)
    parser.add_argument("--application", default="closetos")
    parser.add_argument("--environment", choices=["dev", "prod"], default="dev")
    parser.add_argument("--source", required=True)
    parser.add_argument("--publication", required=True)
    parser.add_argument("--manifest", required=True)
    parser.add_argument("--variables")
    parser.add_argument("--bundle")
    parser.add_argument("--rollout")
    parser.add_argument("--destination")
    args = parser.parse_args()
    try:
        release = metadata(
            args.account,
            args.region,
            args.application,
            args.environment,
            args.source,
            args.publication,
        )
        manifest = read_json(args.manifest)
        if args.action in {"check", "plan"}:
            require(args.variables, "Provide public Terraform release configuration.")
            variables = read_json(args.variables)
            configuration(release, manifest, variables)
            if args.action == "check":
                print(
                    "Public infrastructure configuration and immutable release images verified."
                )
                return
        require(
            args.destination, "Provide a fresh infrastructure artifact destination."
        )
        if args.action == "plan":
            result = plan_release(release, manifest, variables, args.destination)
            print(
                f"Infrastructure plan verified: {len(result['changes'])} resource changes."
            )
        else:
            require(
                args.bundle,
                "Provide this release's reviewed infrastructure plan bundle.",
            )
            if args.action == "apply":
                apply_release(release, manifest, args.bundle, args.destination)
                print(
                    "Reviewed infrastructure plan applied; live release revisions preserved."
                )
            else:
                require(
                    args.rollout,
                    "Provide the successful rollout receipt before enabling scaling.",
                )
                activate_scaling(
                    release,
                    manifest,
                    args.bundle,
                    read_json(args.rollout),
                    args.destination,
                )
                print(
                    "Bounded service autoscaling activated after the successful release."
                )
    except (ReleaseError, OSError) as exception:
        parser.exit(
            1,
            f"{exception if isinstance(exception, ReleaseError) else 'An infrastructure artifact could not be read or written.'}\n",
        )


if __name__ == "__main__":
    main()
