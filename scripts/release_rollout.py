import argparse
import json
import re
import signal
import time
import urllib.error
import urllib.request
from pathlib import Path
from urllib.parse import urlsplit

from release_images import (
    DIGEST,
    ReleaseError,
    metadata,
    read_json,
    require,
    validate_manifest,
    write_json,
)
from release_migration import api, output, request_identity, task_identity
from release_migration import context as migration_context


def task_revision(release, service, arn):
    name = f"{release['application']}-{release['environment']}"
    family = "media" if service == "media-worker" else service
    prefix = f"arn:aws:ecs:{release['region']}:{release['accountId']}:task-definition/{name}-{family}:"
    require(
        isinstance(arn, str) and re.fullmatch(re.escape(prefix) + r"[1-9][0-9]*", arn),
        "A rollout task must identify an exact revision in its own environment and family.",
    )
    return arn


def workflow_task(release, cluster, definition):
    require(isinstance(definition, dict), "The processing workflow is invalid.")
    states = definition.get("States")
    require(isinstance(states, dict), "The processing workflow has no states.")
    require(
        all(isinstance(state, dict) for state in states.values())
        and {
            name
            for name, state in states.items()
            if str(state.get("Resource", "")).startswith("arn:aws:states:::ecs:")
        }
        == {"RunMediaTransform", "RunAIEnrichment"},
        "Processing must launch only the two verified media stages.",
    )
    tasks = []
    for name, command in [
        ("RunMediaTransform", "transform"),
        ("RunAIEnrichment", "enrich"),
    ]:
        state = states.get(name, {})
        parameters = state.get("Parameters", {})
        require(isinstance(parameters, dict), "The media stage parameters are invalid.")
        network = parameters.get("NetworkConfiguration", {})
        require(
            isinstance(network, dict)
            and isinstance(network.get("AwsvpcConfiguration"), dict)
            and isinstance(parameters.get("Overrides"), dict),
            "The media stage networking and overrides are invalid.",
        )
        require(
            state.get("Resource") == "arn:aws:states:::ecs:runTask.sync"
            and parameters.get("Cluster") == cluster
            and parameters.get("LaunchType") == "FARGATE"
            and parameters.get("NetworkConfiguration", {})
            .get("AwsvpcConfiguration", {})
            .get("AssignPublicIp")
            == "DISABLED"
            and parameters.get("Overrides", {}).get("ContainerOverrides")
            == [
                {
                    "Name": "media-worker",
                    "Command": [command],
                    "Environment": [
                        {
                            "Name": "WORKFLOW_INPUT",
                            "Value.$": "States.JsonToString($.job)",
                        }
                    ],
                }
            ],
            "Processing must use the private environment worker without credential or command overrides.",
        )
        tasks.append(
            task_revision(release, "media-worker", parameters.get("TaskDefinition"))
        )
    require(
        tasks[0] == tasks[1], "Processing stages must use the same worker revision."
    )
    return tasks[0]


def context(outputs, release):
    target = migration_context(outputs, release)
    services = output(outputs, "service_names")
    definitions = output(outputs, "service_task_definitions")
    require(
        services == {key: target["name"] + "-" + key for key in ["api", "web"]}
        and isinstance(definitions, dict)
        and set(definitions) == {"api", "web"},
        "Roll out only this environment's API and web services.",
    )
    for key, arn in definitions.items():
        task_revision(release, key, arn)
    origin = output(outputs, "application_url")
    require(isinstance(origin, str), "Provide the application's HTTPS origin.")
    require(
        re.fullmatch(
            r"https://[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+",
            origin,
        ),
        "Smoke tests require a bare HTTPS application origin.",
    )
    machine = output(outputs, "processing_state_machine_arn")
    require(
        machine
        == f"arn:aws:states:{release['region']}:{release['accountId']}:stateMachine:{target['name']}-media",
        "The processing workflow belongs to another environment.",
    )
    workflow = output(outputs, "processing_workflow_definition")
    worker = workflow_task(release, target["cluster"], workflow)
    secrets = output(outputs, "application_secret_arns")
    for key in ["database-app", "media-signing", "web-session"]:
        prefix = f"arn:aws:secretsmanager:{release['region']}:{release['accountId']}:secret:{target['name']}/{key}-"
        require(
            isinstance(secrets.get(key), str) and secrets[key].startswith(prefix),
            "Use this environment's application secret references.",
        )
    return {
        **target,
        "services": services,
        "definitions": {**definitions, "media-worker": worker},
        "origin": origin,
        "machine": machine,
        "workflow": workflow,
        "secrets": secrets,
    }


def validate_receipt(release, manifest, target, receipt):
    require(
        isinstance(receipt, dict)
        and all(receipt.get(key) == value for key, value in release.items())
        and receipt.get("status") == "SUCCEEDED"
        and type(receipt.get("exitCode")) is int
        and receipt["exitCode"] == 0
        and receipt.get("apiImage") == manifest["images"]["api"]["imageReference"]
        and receipt.get("taskDefinitionArn") == target["definition"],
        "Rollout requires a successful migration from this exact release and environment.",
    )
    arn = receipt.get("taskArn")
    require(
        isinstance(arn, str)
        and re.fullmatch(
            re.escape(target["cluster"].replace(":cluster/", ":task/") + "/")
            + r"[0-9a-f]{32}",
            arn,
        ),
        "The migration task belongs to another cluster.",
    )
    return arn


def verify_migration(release, manifest, target, receipt):
    arn = validate_receipt(release, manifest, target, receipt)
    observation = api(
        release["region"],
        "ecs",
        "describe-tasks",
        "--cluster",
        target["cluster"],
        "--tasks",
        arn,
    )
    tasks = observation.get("tasks")
    require(
        not observation.get("failures") and isinstance(tasks, list) and len(tasks) == 1,
        "ECS could not reconfirm the completed migration.",
    )
    task = tasks[0]
    _, started_by = request_identity(release, target)
    task_identity(task, target, started_by, arn)
    containers = task.get("containers")
    require(
        isinstance(containers, list)
        and len(containers) == 1
        and isinstance(containers[0], dict),
        "The migration completion is invalid.",
    )
    container = containers[0]
    require(
        task.get("lastStatus") == "STOPPED"
        and task.get("stopCode") == "EssentialContainerExited"
        and container.get("name") == "migration"
        and container.get("lastStatus") == "STOPPED"
        and type(container.get("exitCode")) is int
        and container["exitCode"] == 0
        and container.get("image") == receipt["apiImage"]
        and isinstance(container.get("imageDigest"), str)
        and DIGEST.fullmatch(container["imageDigest"])
        and container["imageDigest"] == receipt.get("runtimeImageDigest"),
        "ECS has not confirmed a successful exit of this release's migration image.",
    )


def values(entries, field):
    require(
        isinstance(entries, list)
        and all(
            isinstance(item, dict)
            and isinstance(item.get("name"), str)
            and isinstance(item.get(field), str)
            for item in entries
        ),
        "Task settings are invalid.",
    )
    require(
        len({item["name"] for item in entries}) == len(entries),
        "Task settings contain duplicate names.",
    )
    return {item["name"]: item[field] for item in entries}


def verify_definition(release, manifest, target, service, arn, *, previous=False):
    task_revision(release, service, arn)
    definition = api(
        release["region"], "ecs", "describe-task-definition", "--task-definition", arn
    ).get("taskDefinition")
    require(
        isinstance(definition, dict), "ECS did not return the release task definition."
    )
    roles = f"arn:aws:iam::{release['accountId']}:role/{target['name']}-{service}-"
    containers = definition.get("containerDefinitions")
    require(
        definition.get("taskDefinitionArn") == arn
        and definition.get("status") == "ACTIVE"
        and definition.get("requiresCompatibilities") == ["FARGATE"]
        and definition.get("networkMode") == "awsvpc"
        and definition.get("runtimePlatform")
        == {"cpuArchitecture": "X86_64", "operatingSystemFamily": "LINUX"}
        and definition.get("taskRoleArn") == roles + "task"
        and definition.get("executionRoleArn") == roles + "execution"
        and isinstance(containers, list)
        and len(containers) == 1
        and isinstance(containers[0], dict),
        "Release tasks must use their dedicated roles and Linux amd64 Fargate configuration.",
    )
    container = containers[0]
    image = container.get("image")
    expected = manifest["images"][service]["imageReference"]
    require(
        container.get("name") == service
        and container.get("essential") is True
        and container.get("readonlyRootFilesystem") is True
        and not container.get("privileged")
        and container.get("user")
        == {"api": "closetos", "web": "node", "media-worker": "10001"}[service]
        and isinstance(image, str)
        and re.fullmatch(
            re.escape(expected.split("@")[0]) + r"@sha256:[0-9a-f]{64}", image
        )
        and (previous or image == expected),
        "Release tasks must use the exact immutable environment image and an unprivileged container.",
    )
    secrets = values(container.get("secrets", []), "valueFrom")
    expected_secrets = {
        "api": {
            "DATABASE_PASSWORD": target["secrets"]["database-app"],
            "CLOUDFRONT_PRIVATE_KEY": target["secrets"]["media-signing"],
        },
        "web": {"NEXTAUTH_SECRET": target["secrets"]["web-session"]},
        "media-worker": {},
    }[service]
    require(
        secrets == expected_secrets,
        "Release tasks must reference only their dedicated application secrets.",
    )
    env = values(container.get("environment", []), "value")
    if service == "api":
        require(
            env.get("SPRING_FLYWAY_ENABLED") == "false"
            and env.get("DATABASE_USERNAME") == "closetos_app"
            and env.get("DATABASE_URL") == target["databaseUrl"],
            "API tasks must use restricted database credentials after standalone migration.",
        )
    elif service == "web":
        require(
            env.get("NEXTAUTH_URL") == target["origin"]
            and env.get("API_URL") == f"http://api.{target['name']}.internal:8080",
            "Web tasks must use the HTTPS origin and private API discovery.",
        )


def describe_service(release, target, service):
    response = api(
        release["region"],
        "ecs",
        "describe-services",
        "--cluster",
        target["cluster"],
        "--services",
        target["services"][service],
    )
    found = response.get("services")
    require(
        not response.get("failures") and isinstance(found, list) and len(found) == 1,
        "ECS could not observe the release service.",
    )
    result = found[0]
    require(
        isinstance(result, dict)
        and result.get("serviceName") == target["services"][service]
        and result.get("serviceArn")
        == target["cluster"].replace(":cluster/", ":service/")
        + "/"
        + target["services"][service]
        and result.get("clusterArn") == target["cluster"]
        and result.get("status") == "ACTIVE"
        and result.get("deploymentController", {}).get("type") == "ECS"
        and result.get("launchType") == "FARGATE"
        and result.get("enableExecuteCommand") is False
        and result.get("networkConfiguration", {})
        .get("awsvpcConfiguration", {})
        .get("assignPublicIp")
        == "DISABLED",
        "Observe only this environment's private ECS services with remote exec disabled.",
    )
    for field in ["desiredCount", "runningCount", "pendingCount"]:
        require(
            type(result.get(field)) is int and result[field] >= 0,
            "ECS returned an invalid service count.",
        )
    deployments = result.get("deployments")
    require(
        isinstance(deployments, list)
        and deployments
        and all(isinstance(item, dict) for item in deployments),
        "ECS returned no service deployment.",
    )
    return result


def stable(service, arn, *, stopped=False, deployment=None):
    deployments = service["deployments"]
    primary = [item for item in deployments if item.get("status") == "PRIMARY"]
    return (
        service.get("taskDefinition") == arn
        and len(deployments) == 1
        and len(primary) == 1
        and primary[0].get("taskDefinition") == arn
        and primary[0].get("rolloutState") == "COMPLETED"
        and (deployment is None or primary[0].get("id") == deployment)
        and service["pendingCount"] == 0
        and service["runningCount"] == service["desiredCount"]
        and (service["desiredCount"] == 0 if stopped else service["desiredCount"] > 0)
    )


def wait_service(release, target, service, arn, deployment, timeout, *, stopped=False):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        observed = describe_service(release, target, service)
        if stable(observed, arn, stopped=stopped, deployment=deployment):
            return observed
        require(
            not any(
                item.get("id") == deployment and item.get("rolloutState") == "FAILED"
                for item in observed["deployments"]
            ),
            "ECS rejected the expected release deployment.",
        )
        time.sleep(min(5, max(0, deadline - time.monotonic())))
    raise ReleaseError(
        "ECS did not confirm the expected healthy task revision before the deadline."
    )


def update_service(release, target, service, arn, count=None):
    request = {
        "cluster": target["cluster"],
        "service": target["services"][service],
        "taskDefinition": arn,
        "enableExecuteCommand": False,
    }
    if count is not None:
        request["desiredCount"] = count
    response = api(
        release["region"],
        "ecs",
        "update-service",
        "--cli-input-json",
        json.dumps(request),
    ).get("service")
    require(
        isinstance(response, dict)
        and response.get("clusterArn") == target["cluster"]
        and response.get("serviceName") == target["services"][service]
        and response.get("taskDefinition") == arn,
        "ECS did not acknowledge the requested release revision.",
    )
    primary = [
        item
        for item in response.get("deployments", [])
        if isinstance(item, dict)
        and item.get("status") == "PRIMARY"
        and item.get("taskDefinition") == arn
    ]
    require(
        len(primary) == 1
        and isinstance(primary[0].get("id"), str)
        and primary[0]["id"],
        "ECS did not acknowledge one primary deployment.",
    )
    return primary[0]["id"]


def describe_workflow(release, target):
    response = api(
        release["region"],
        "stepfunctions",
        "describe-state-machine",
        "--state-machine-arn",
        target["machine"],
    )
    require(
        response.get("stateMachineArn") == target["machine"]
        and response.get("status") == "ACTIVE"
        and response.get("type") == "STANDARD"
        and response.get("roleArn")
        == f"arn:aws:iam::{release['accountId']}:role/{target['name']}-media-workflow",
        "Observe only the environment's active processing workflow.",
    )
    try:
        definition = json.loads(response.get("definition", ""))
    except (ValueError, TypeError):
        raise ReleaseError(
            "Step Functions returned an invalid workflow definition."
        ) from None
    workflow_task(release, target["cluster"], definition)
    return definition


def update_workflow(release, target, definition, timeout):
    api(
        release["region"],
        "stepfunctions",
        "update-state-machine",
        "--state-machine-arn",
        target["machine"],
        "--definition",
        json.dumps(definition),
    )
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if describe_workflow(release, target) == definition:
            return
        time.sleep(min(5, max(0, deadline - time.monotonic())))
    raise ReleaseError(
        "Step Functions did not confirm the release workflow before the deadline."
    )


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def http_get(url):
    opener = urllib.request.build_opener(NoRedirect())
    request = urllib.request.Request(
        url,
        headers={
            "User-Agent": "closetos-release-smoke",
            "Accept": "text/html,application/json",
        },
    )
    try:
        try:
            response = opener.open(request, timeout=10)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            body = response.read(131073)
            require(len(body) <= 131072, "A smoke response exceeded the allowed size.")
            return response.status, response.headers, body
    except (OSError, urllib.error.URLError):
        raise ReleaseError(
            "The application smoke endpoint could not be reached."
        ) from None


def smoke(origin):
    for path, expected in [
        ("/signin", 200),
        ("/api/v1/me", 401),
        ("/api/backend/me", 401),
    ]:
        status, headers, body = http_get(origin + path)
        require(
            status == expected,
            "A public application or authentication smoke check failed.",
        )
        if path == "/signin":
            require(
                "text/html" in headers.get("Content-Type", "")
                and b"CLOSET" in body
                and b"Open your wardrobe" in body,
                "The release did not serve the wardrobe sign-in page.",
            )
        else:
            require(
                headers.get("Content-Type", "").split(";", 1)[0]
                in {"application/json", "application/problem+json"},
                "An authentication smoke check returned an unexpected response.",
            )
    status, headers, _ = http_get("http://" + urlsplit(origin).netloc + "/")
    require(
        status in {301, 302, 307, 308}
        and headers.get("Location") in {origin + "/", origin + ":443/"},
        "HTTP must redirect to the same application's HTTPS origin.",
    )


def rollback(release, target, snapshot, attempted, workflow_attempted, timeout):
    confirmed = True
    if workflow_attempted:
        try:
            update_workflow(release, target, snapshot["workflow"], timeout)
        except (Exception, KeyboardInterrupt):  # noqa: BLE001 -- Continue restoring services even if workflow recovery fails.
            confirmed = False
    for service in reversed(attempted):
        previous = snapshot["services"][service]
        try:
            deployment = update_service(
                release,
                target,
                service,
                previous["taskDefinition"],
                0 if previous["desiredCount"] == 0 else None,
            )
            wait_service(
                release,
                target,
                service,
                previous["taskDefinition"],
                deployment,
                timeout,
                stopped=previous["desiredCount"] == 0,
            )
        except (Exception, KeyboardInterrupt):  # noqa: BLE001 -- Attempt every service restoration before reporting uncertainty.
            confirmed = False
    return confirmed


def rollout(release, manifest, outputs, receipt, destination, timeout=900):
    require(
        30 <= timeout <= 1800, "Rollout timeout must be between 30 and 1,800 seconds."
    )
    validate_manifest(manifest, release)
    target = context(outputs, release)
    destination = Path(destination)
    snapshot_file = destination.with_name(destination.stem + "-previous.json")
    require(
        not destination.exists() and not snapshot_file.exists(),
        "Use fresh rollout result destinations.",
    )
    validate_receipt(release, manifest, target, receipt)
    require(
        api(release["region"], "sts", "get-caller-identity").get("Account")
        == release["accountId"],
        "Rollout credentials belong to another AWS account.",
    )
    verify_migration(release, manifest, target, receipt)
    for service, arn in target["definitions"].items():
        verify_definition(release, manifest, target, service, arn)
    previous = {}
    for service in ["api", "web"]:
        observed = describe_service(release, target, service)
        arn = task_revision(release, service, observed.get("taskDefinition"))
        require(
            stable(observed, arn, stopped=observed["desiredCount"] == 0),
            "Finish the current ECS deployment before starting another release.",
        )
        verify_definition(release, manifest, target, service, arn, previous=True)
        previous[service] = {
            "taskDefinition": arn,
            "desiredCount": observed["desiredCount"],
        }
    workflow = describe_workflow(release, target)
    verify_definition(
        release,
        manifest,
        target,
        "media-worker",
        workflow_task(release, target["cluster"], workflow),
        previous=True,
    )
    snapshot = {
        **release,
        "clusterArn": target["cluster"],
        "stateMachineArn": target["machine"],
        "services": previous,
        "workflow": workflow,
    }
    write_json(snapshot_file, snapshot)
    attempted = []
    deployments = {}
    workflow_attempted = False
    try:
        for service in ["api", "web"]:
            # An ambiguous update can still have changed ECS; include it in rollback first.
            attempted.append(service)
            deployment = update_service(
                release,
                target,
                service,
                target["definitions"][service],
                (2 if release["environment"] == "prod" else 1)
                if previous[service]["desiredCount"] == 0
                else None,
            )
            deployments[service] = deployment
            wait_service(
                release,
                target,
                service,
                target["definitions"][service],
                deployment,
                timeout,
            )
        workflow_attempted = True
        update_workflow(release, target, target["workflow"], timeout)
        smoke(target["origin"])
        for service in ["api", "web"]:
            wait_service(
                release,
                target,
                service,
                target["definitions"][service],
                deployments[service],
                timeout,
            )
        result = {
            **release,
            "status": "SUCCEEDED",
            "clusterArn": target["cluster"],
            "taskDefinitions": target["definitions"],
            "stateMachineArn": target["machine"],
            "applicationUrl": target["origin"],
        }
        write_json(destination, result)
        return result
    except (Exception, KeyboardInterrupt):  # noqa: BLE001 -- Any failure after a remote mutation requires compensation.
        confirmed = rollback(
            release, target, snapshot, attempted, workflow_attempted, timeout
        )
        write_json(
            destination,
            {
                **release,
                "status": "ROLLED_BACK" if confirmed else "ROLLBACK_UNCONFIRMED",
                "previousState": snapshot_file.name,
            },
        )
        raise ReleaseError(
            "Release failed; previous task revisions and workflow were restored."
            if confirmed
            else "Release and rollback are unconfirmed. Inspect the retained previous state before another deployment."
        ) from None


def interrupted(_signal, _frame):
    signal.signal(signal.SIGTERM, signal.SIG_IGN)
    signal.signal(signal.SIGINT, signal.SIG_IGN)
    raise ReleaseError("Release execution was interrupted.")


def main():
    parser = argparse.ArgumentParser(
        description="Roll out migrated release images and restore the previous release on failure."
    )
    parser.add_argument("action", choices=["check", "deploy"])
    parser.add_argument("--account", required=True)
    parser.add_argument("--region", required=True)
    parser.add_argument("--application", default="closetos")
    parser.add_argument("--environment", choices=["dev", "prod"], default="dev")
    parser.add_argument("--source", required=True)
    parser.add_argument("--publication", required=True)
    parser.add_argument("--manifest", required=True)
    parser.add_argument("--infrastructure", required=True)
    parser.add_argument("--migration", required=True)
    parser.add_argument("--destination")
    parser.add_argument("--timeout", type=int, default=900)
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
        manifest, outputs, receipt = (
            read_json(args.manifest),
            read_json(args.infrastructure),
            read_json(args.migration),
        )
        validate_manifest(manifest, release)
        target = context(outputs, release)
        validate_receipt(release, manifest, target, receipt)
        if args.action == "check":
            print(
                "Release rollout configuration verified; migration completion is rechecked before deployment."
            )
            return
        require(args.destination, "Provide a rollout result destination.")
        signal.signal(signal.SIGTERM, interrupted)
        signal.signal(signal.SIGINT, interrupted)
        rollout(release, manifest, outputs, receipt, args.destination, args.timeout)
        print("Release health and public smoke checks passed.")
    except (ReleaseError, OSError) as exception:
        parser.exit(
            1,
            f"{exception if isinstance(exception, ReleaseError) else 'A release artifact could not be written.'}\n",
        )


if __name__ == "__main__":
    main()
