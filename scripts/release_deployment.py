import argparse
import json
import re
from pathlib import Path

from release_images import ReleaseError, metadata, read_json, require, run, write_json

ROLES = {
    "planner": "planner",
    "infrastructure": "infrastructure",
    "initializer": "secret-initializer",
    "migrator": "migrator",
    "deployer": "deployer",
}


def github(repository, path):
    try:
        response = json.loads(
            run(
                [
                    "gh",
                    "api",
                    f"repos/{repository}/{path}",
                    "-H",
                    "Accept: application/vnd.github+json",
                    "-H",
                    "X-GitHub-Api-Version: 2026-03-10",
                ]
            )
        )
    except ValueError:
        raise ReleaseError(
            "GitHub returned invalid production protection metadata."
        ) from None
    require(
        isinstance(response, dict),
        "GitHub returned invalid production protection metadata.",
    )
    return response


def production_protection(repository):
    require(
        isinstance(repository, str)
        and re.fullmatch(r"[A-Za-z0-9-]+/[A-Za-z0-9_.-]+", repository),
        "Provide the exact deployment repository.",
    )
    environment = github(repository, "environments/production")
    rules = environment.get("protection_rules", [])
    require(
        environment.get("name") == "production"
        and isinstance(rules, list)
        and any(
            isinstance(rule, dict)
            and rule.get("type") == "required_reviewers"
            and isinstance(rule.get("reviewers"), list)
            and rule["reviewers"]
            for rule in rules
        ),
        "Configure required reviewers on the production GitHub Environment before deploying.",
    )
    require(
        environment.get("deployment_branch_policy")
        == {"protected_branches": False, "custom_branch_policies": True},
        "Limit the production GitHub Environment to the main branch.",
    )
    policies = github(
        repository, "environments/production/deployment-branch-policies?per_page=100"
    )
    branches = policies.get("branch_policies")
    require(
        policies.get("total_count") == 1
        and isinstance(branches, list)
        and len(branches) == 1
        and branches[0].get("name") == "main"
        and branches[0].get("type") == "branch",
        "The production environment must allow only the exact main branch, without wildcard branches or tags.",
    )


def check_roles(release, roles):
    require(
        isinstance(roles, dict) and set(roles) == set(ROLES),
        "Configure each separate planning, infrastructure, initialization, migration, and rollout role.",
    )
    for key, suffix in ROLES.items():
        expected = f"arn:aws:iam::{release['accountId']}:role/{release['application']}-{release['environment']}-github-{suffix}"
        require(
            roles[key] == expected,
            f"Choose the environment's dedicated GitHub {key} role.",
        )


def report(release, directory, destination):
    directory = Path(directory)
    statuses = {}
    for stage, filename in [
        ("secrets", "secrets.json"),
        ("migration", "migration.json"),
        ("rollout", "rollout.json"),
        ("scaling", "scaling/result.json"),
    ]:
        path = directory / filename
        if not path.exists():
            statuses[stage] = "UNCONFIRMED"
            continue
        receipt = read_json(path)
        require(
            isinstance(receipt, dict)
            and all(receipt.get(key) == value for key, value in release.items()),
            "Deployment receipts must belong to this exact commit, publication, and environment.",
        )
        status = receipt.get("status")
        require(
            status in {"SUCCEEDED", "ROLLED_BACK", "ROLLBACK_UNCONFIRMED"},
            "A deployment receipt has an unrecognized result.",
        )
        statuses[stage] = status
    result = {
        **release,
        "status": "SUCCEEDED"
        if all(status == "SUCCEEDED" for status in statuses.values())
        else "FAILED",
        "stages": statuses,
    }
    write_json(destination, result)
    return result


def main():
    parser = argparse.ArgumentParser(
        description="Validate separated deployment roles and production approvals, and publish bound deployment results."
    )
    parser.add_argument("action", choices=["check", "report"])
    parser.add_argument("--account", required=True)
    parser.add_argument("--region", required=True)
    parser.add_argument("--application", default="closetos")
    parser.add_argument("--environment", choices=["dev", "prod"], default="dev")
    parser.add_argument("--source", required=True)
    parser.add_argument("--publication", required=True)
    parser.add_argument("--repository")
    for role in ROLES:
        parser.add_argument("--" + role)
    parser.add_argument("--directory")
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
        if args.action == "check":
            check_roles(release, {key: getattr(args, key) for key in ROLES})
            if release["environment"] == "prod":
                production_protection(args.repository)
            print(
                "Dedicated deployment roles and required environment protection verified."
            )
        else:
            require(
                args.directory and args.destination,
                "Provide the deployment receipt directory and result destination.",
            )
            result = report(release, args.directory, args.destination)
            print(f"Deployment result: {result['status']}.")
    except (ReleaseError, OSError) as exception:
        parser.exit(
            1,
            f"{exception if isinstance(exception, ReleaseError) else 'A deployment artifact could not be read or written.'}\n",
        )


if __name__ == "__main__":
    main()
