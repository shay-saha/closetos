import argparse
import hashlib
import json
import re
import signal
import time
from pathlib import Path

from release_images import (
    DIGEST,
    ReleaseError,
    aws,
    metadata,
    read_json,
    require,
    validate_manifest,
    write_json,
)

MIGRATION_COMMAND = [
    "-XX:MaxRAMPercentage=75",
    "-Dloader.main=com.closetos.platform.infrastructure.DatabaseMigration",
    "-cp",
    "/app/app.jar",
    "org.springframework.boot.loader.launch.PropertiesLauncher",
]


def api(region, service, *arguments):
    return aws(
        region,
        service,
        *arguments,
        "--cli-connect-timeout",
        "5",
        "--cli-read-timeout",
        "20",
    )


def output(outputs, name):
    require(isinstance(outputs, dict), "Terraform outputs must be a JSON object.")
    entry = outputs.get(name)
    require(
        isinstance(entry, dict) and "value" in entry,
        f"Terraform did not provide {name}.",
    )
    return entry["value"]


def context(outputs, release):
    name = f"{release['application']}-{release['environment']}"
    prefix = f"arn:aws:ecs:{release['region']}:{release['accountId']}:"
    cluster = output(outputs, "ecs_cluster_arn")
    task = output(outputs, "migration_task_definition_arn")
    require(
        cluster == prefix + f"cluster/{name}",
        "The ECS cluster belongs to another environment.",
    )
    require(
        isinstance(task, str)
        and re.fullmatch(
            re.escape(prefix + f"task-definition/{name}-migration:") + r"[1-9][0-9]*",
            task,
        ),
        "Choose an exact revision of the environment's migration task.",
    )
    network = output(outputs, "migration_network")
    require(
        isinstance(network, dict) and network.get("assign_public_ip") is False,
        "Migration tasks require private networking.",
    )
    subnets = network.get("subnets")
    groups = network.get("security_groups")
    require(
        isinstance(subnets, list)
        and 1 <= len(subnets) <= 16
        and all(
            isinstance(item, str) and re.fullmatch(r"subnet-[0-9a-f]{8,17}", item)
            for item in subnets
        )
        and len(set(subnets)) == len(subnets),
        "Provide exact private subnet IDs.",
    )
    require(
        isinstance(groups, list)
        and len(groups) == 1
        and isinstance(groups[0], str)
        and re.fullmatch(r"sg-[0-9a-f]{8,17}", groups[0]),
        "Use the migration's API security group.",
    )
    vpc = output(outputs, "vpc_id")
    address = output(outputs, "database_address")
    require(
        isinstance(vpc, str) and re.fullmatch(r"vpc-[0-9a-f]{8,17}", vpc),
        "Provide the application's VPC ID.",
    )
    require(
        isinstance(address, str)
        and re.fullmatch(r"[a-z0-9.-]+\.rds\.amazonaws\.com", address),
        "Provide the RDS hostname for the migration.",
    )
    master = output(outputs, "database_secret_arn")
    secrets = output(outputs, "application_secret_arns")
    require(
        isinstance(secrets, dict),
        "Terraform must provide application secret references.",
    )
    secret_prefix = (
        f"arn:aws:secretsmanager:{release['region']}:{release['accountId']}:secret:"
    )
    require(
        isinstance(master, str)
        and master.startswith(secret_prefix)
        and isinstance(secrets.get("database-app"), str)
        and secrets["database-app"].startswith(secret_prefix + name + "/database-app-"),
        "Database secret references must belong to this environment and AWS account.",
    )
    return {
        "name": name,
        "cluster": cluster,
        "definition": task,
        "vpc": vpc,
        "subnets": sorted(subnets),
        "groups": groups,
        "databaseUrl": f"jdbc:postgresql://{address}:5432/closetos?sslmode=verify-full&sslrootcert=/app/rds-global-bundle.pem",
        "masterSecret": master,
        "applicationSecret": secrets["database-app"],
    }


def named_values(entries, field):
    require(
        isinstance(entries, list)
        and len(entries) == 2
        and all(
            isinstance(item, dict)
            and isinstance(item.get("name"), str)
            and isinstance(item.get(field), str)
            for item in entries
        ),
        "Migration database settings are invalid.",
    )
    require(
        len({item["name"] for item in entries}) == 2,
        "Migration database settings contain duplicate names.",
    )
    return {item["name"]: item[field] for item in entries}


def verify_prerequisites(release, manifest, target):
    region = release["region"]
    require(
        api(region, "sts", "get-caller-identity").get("Account")
        == release["accountId"],
        "Migration credentials belong to another AWS account.",
    )
    definition = api(
        region,
        "ecs",
        "describe-task-definition",
        "--task-definition",
        target["definition"],
    ).get("taskDefinition")
    require(
        isinstance(definition, dict), "ECS did not return the migration definition."
    )
    role_prefix = (
        f"arn:aws:iam::{release['accountId']}:role/{target['name']}-migration-"
    )
    containers = definition.get("containerDefinitions")
    require(
        definition.get("taskDefinitionArn") == target["definition"]
        and definition.get("status") == "ACTIVE"
        and definition.get("networkMode") == "awsvpc"
        and definition.get("requiresCompatibilities") == ["FARGATE"]
        and definition.get("taskRoleArn") == role_prefix + "task"
        and definition.get("executionRoleArn") == role_prefix + "execution"
        and definition.get("runtimePlatform")
        == {"cpuArchitecture": "X86_64", "operatingSystemFamily": "LINUX"}
        and isinstance(containers, list)
        and len(containers) == 1
        and isinstance(containers[0], dict),
        "The task must be the active, isolated Linux Fargate migration definition.",
    )
    container = containers[0]
    require(
        container.get("name") == "migration"
        and container.get("image") == manifest["images"]["api"]["imageReference"]
        and container.get("essential") is True
        and container.get("readonlyRootFilesystem") is True
        and container.get("user") == "closetos"
        and container.get("entryPoint") == ["java"]
        and container.get("command") == MIGRATION_COMMAND
        and not container.get("portMappings")
        and not container.get("privileged", False),
        "The migration must run the release's exact API image and standalone migration entrypoint.",
    )
    require(
        named_values(container.get("environment"), "value")
        == {"DATABASE_URL": target["databaseUrl"], "DATABASE_USERNAME": "closetos"}
        and named_values(container.get("secrets"), "valueFrom")
        == {
            "DATABASE_PASSWORD": target["masterSecret"] + ":password::",
            "APPLICATION_DATABASE_PASSWORD": target["applicationSecret"],
        },
        "Database credentials must be resolved by ECS from the prepared secret references.",
    )
    subnets = api(
        region, "ec2", "describe-subnets", "--subnet-ids", *target["subnets"]
    ).get("Subnets", [])
    require(
        isinstance(subnets, list)
        and len(subnets) == len(target["subnets"])
        and all(
            isinstance(subnet, dict)
            and subnet.get("SubnetId") in target["subnets"]
            and subnet.get("VpcId") == target["vpc"]
            and subnet.get("OwnerId") == release["accountId"]
            and subnet.get("MapPublicIpOnLaunch") is False
            for subnet in subnets
        )
        and {subnet["SubnetId"] for subnet in subnets} == set(target["subnets"]),
        "The migration subnets must belong to this account's VPC and disable public IP assignment.",
    )
    groups = api(
        region, "ec2", "describe-security-groups", "--group-ids", *target["groups"]
    ).get("SecurityGroups", [])
    require(
        isinstance(groups, list)
        and len(groups) == 1
        and isinstance(groups[0], dict)
        and groups[0].get("GroupId") == target["groups"][0]
        and groups[0].get("VpcId") == target["vpc"]
        and groups[0].get("OwnerId") == release["accountId"]
        and groups[0].get("GroupName") == target["name"] + "-api",
        "The migration must use the application's own API security group.",
    )


def task_identity(task, target, started_by, expected=None):
    require(isinstance(task, dict), "ECS returned an invalid migration task.")
    arn = task.get("taskArn")
    prefix = target["cluster"].replace(":cluster/", ":task/") + "/"
    require(
        isinstance(arn, str)
        and re.fullmatch(re.escape(prefix) + r"[0-9a-f]{32}", arn)
        and task.get("clusterArn") == target["cluster"]
        and task.get("taskDefinitionArn") == target["definition"]
        and task.get("startedBy") == started_by
        and (expected is None or arn == expected),
        "ECS returned a task that does not belong to this migration request.",
    )
    return arn


def run_migration(release, manifest, outputs, destination, timeout=900):
    require(
        30 <= timeout <= 1800, "Migration timeout must be between 30 and 1,800 seconds."
    )
    validate_manifest(manifest, release)
    target = context(outputs, release)
    destination = Path(destination)
    request_file = destination.with_name(destination.stem + "-request.json")
    task_file = destination.with_name(destination.stem + "-task.json")
    require(
        not any(path.exists() for path in [destination, request_file, task_file]),
        "Migration result files already exist; use a fresh result destination.",
    )
    verify_prerequisites(release, manifest, target)
    token = hashlib.sha256(
        json.dumps(
            {
                **release,
                "definition": target["definition"],
                "cluster": target["cluster"],
            },
            sort_keys=True,
        ).encode()
    ).hexdigest()
    started_by = "closetos-migration-" + token[:16]
    network = {
        "awsvpcConfiguration": {
            "subnets": target["subnets"],
            "securityGroups": target["groups"],
            "assignPublicIp": "DISABLED",
        }
    }
    tags = [
        {"key": key, "value": value}
        for key, value in {
            "Application": release["application"],
            "Environment": release["environment"],
            "Workload": "database-migration",
            "Release": release["publicationId"],
        }.items()
    ]
    request = {
        "cluster": target["cluster"],
        "taskDefinition": target["definition"],
        "clientToken": token,
        "startedBy": started_by,
        "count": 1,
        "launchType": "FARGATE",
        "platformVersion": "1.4.0",
        "enableExecuteCommand": False,
        "networkConfiguration": network,
        "tags": tags,
    }
    # Retain the complete request so an uncertain launch can be replayed with the same token.
    write_json(request_file, request)
    response = api(
        release["region"], "ecs", "run-task", "--cli-input-json", json.dumps(request)
    )
    tasks = response.get("tasks", [])
    require(
        isinstance(tasks, list) and len(tasks) == 1,
        "ECS did not confirm one launched migration task. Its request token is retained for investigation.",
    )
    arn = task_identity(tasks[0], target, started_by)
    stopped = False
    try:
        write_json(
            task_file,
            {
                **release,
                "taskArn": arn,
                "taskDefinitionArn": target["definition"],
                "clientToken": token,
            },
        )
        require(
            not response.get("failures"), "ECS reported a migration launch failure."
        )
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            observation = api(
                release["region"],
                "ecs",
                "describe-tasks",
                "--cluster",
                target["cluster"],
                "--tasks",
                arn,
            )
            found = observation.get("tasks", [])
            failures = observation.get("failures", [])
            if (
                found == []
                and isinstance(failures, list)
                and failures
                and all(
                    isinstance(failure, dict)
                    and failure.get("arn") == arn
                    and failure.get("reason") == "MISSING"
                    for failure in failures
                )
            ):
                time.sleep(min(5, max(0, deadline - time.monotonic())))
                continue
            require(
                not failures and isinstance(found, list) and len(found) == 1,
                "ECS could not confirm the migration task's state.",
            )
            task = found[0]
            task_identity(task, target, started_by, arn)
            if task.get("lastStatus") != "STOPPED":
                time.sleep(min(5, max(0, deadline - time.monotonic())))
                continue
            stopped = True
            containers = task.get("containers", [])
            require(
                task.get("stopCode") == "EssentialContainerExited"
                and isinstance(containers, list)
                and len(containers) == 1
                and isinstance(containers[0], dict),
                "The migration task did not finish normally.",
            )
            container = containers[0]
            require(
                container.get("name") == "migration"
                and container.get("lastStatus") == "STOPPED"
                and type(container.get("exitCode")) is int
                and container["exitCode"] == 0
                and container.get("image")
                == manifest["images"]["api"]["imageReference"]
                and isinstance(container.get("imageDigest"), str)
                and DIGEST.fullmatch(container["imageDigest"]),
                "The release migration did not confirm a successful container exit.",
            )
            result = {
                **release,
                "status": "SUCCEEDED",
                "taskArn": arn,
                "taskDefinitionArn": target["definition"],
                "apiImage": container["image"],
                "runtimeImageDigest": container["imageDigest"],
                "exitCode": 0,
            }
            write_json(destination, result)
            return result
        raise ReleaseError(
            "The migration deadline expired without a successful task exit."
        )
    finally:
        if not stopped:
            try:
                api(
                    release["region"],
                    "ecs",
                    "stop-task",
                    "--cluster",
                    target["cluster"],
                    "--task",
                    arn,
                    "--reason",
                    "Release migration verification did not finish",
                )
            except ReleaseError:
                raise ReleaseError(
                    "Migration completion and cleanup are unconfirmed. Inspect the retained task ARN before another deployment."
                ) from None


def interrupted(_signal, _frame):
    raise ReleaseError("Migration verification was interrupted.")


def main():
    parser = argparse.ArgumentParser(
        description="Verify and run a release's private standalone ECS migration."
    )
    parser.add_argument("action", choices=["check", "run"])
    for option in [
        "account",
        "region",
        "source",
        "publication",
        "manifest",
        "infrastructure",
    ]:
        parser.add_argument("--" + option, required=True)
    parser.add_argument("--application", default="closetos")
    parser.add_argument("--environment", choices=["dev", "prod"], default="dev")
    parser.add_argument("--destination")
    parser.add_argument("--timeout", type=int, default=900)
    args = parser.parse_args()
    signal.signal(signal.SIGTERM, interrupted)
    signal.signal(signal.SIGINT, interrupted)
    try:
        release = metadata(
            args.account,
            args.region,
            args.application,
            args.environment,
            args.source,
            args.publication,
        )
        manifest = validate_manifest(read_json(args.manifest), release)
        outputs = read_json(args.infrastructure)
        context(outputs, release)
        if args.action == "check":
            print("Release migration inputs verified without AWS access.")
            return
        require(args.destination, "Provide a fresh destination for migration results.")
        run_migration(release, manifest, outputs, args.destination, args.timeout)
        print("The release migration completed with a confirmed successful exit.")
    except ReleaseError as exception:
        parser.exit(1, f"{exception}\n")


if __name__ == "__main__":
    main()
