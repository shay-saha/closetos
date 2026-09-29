import copy
import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import release_images as images
import release_migration as migration


class MigrationTools:
    def __init__(self, release, manifest, outputs):
        self.release = release
        self.manifest = manifest
        self.target = migration.context(outputs, release)
        self.calls = []
        self.states = ["PROVISIONING", "RUNNING", "STOPPED"]
        self.missing = 0
        self.fail = None
        self.cleanup_fails = False
        self.launch_failure = False
        self.exit_code = 0
        self.stop_code = "EssentialContainerExited"
        self.tick = 0
        self.arn = (
            self.target["cluster"].replace(":cluster/", ":task/") + "/" + "a" * 32
        )
        self.started_by = None
        name = self.target["name"]
        role = f"arn:aws:iam::{release['accountId']}:role/{name}-migration-"
        self.definition = {
            "taskDefinitionArn": self.target["definition"],
            "status": "ACTIVE",
            "networkMode": "awsvpc",
            "requiresCompatibilities": ["FARGATE"],
            "taskRoleArn": role + "task",
            "executionRoleArn": role + "execution",
            "runtimePlatform": {
                "cpuArchitecture": "X86_64",
                "operatingSystemFamily": "LINUX",
            },
            "containerDefinitions": [
                {
                    "name": "migration",
                    "image": manifest["images"]["api"]["imageReference"],
                    "essential": True,
                    "readonlyRootFilesystem": True,
                    "user": "closetos",
                    "entryPoint": ["java"],
                    "command": list(migration.MIGRATION_COMMAND),
                    "environment": [
                        {"name": "DATABASE_URL", "value": self.target["databaseUrl"]},
                        {"name": "DATABASE_USERNAME", "value": "closetos"},
                    ],
                    "secrets": [
                        {
                            "name": "DATABASE_PASSWORD",
                            "valueFrom": self.target["masterSecret"] + ":password::",
                        },
                        {
                            "name": "APPLICATION_DATABASE_PASSWORD",
                            "valueFrom": self.target["applicationSecret"],
                        },
                    ],
                }
            ],
        }
        self.owner = release["accountId"]
        self.vpc = self.target["vpc"]
        self.public_subnet = False
        self.observed_definition = self.target["definition"]
        self.observed_image = manifest["images"]["api"]["imageReference"]

    def sleep(self, seconds):
        self.tick += seconds

    def task(self, status):
        return {
            "taskArn": self.arn,
            "clusterArn": self.target["cluster"],
            "taskDefinitionArn": self.observed_definition,
            "startedBy": self.started_by,
            "lastStatus": status,
            "stopCode": self.stop_code,
            "containers": [
                {
                    "name": "migration",
                    "lastStatus": status,
                    "image": self.observed_image,
                    "imageDigest": "sha256:" + "b" * 64,
                    "exitCode": self.exit_code,
                }
            ],
        }

    def __call__(self, region, service, action, *arguments):
        self.calls.append((service, action, list(arguments)))
        if self.fail == action or (action == "stop-task" and self.cleanup_fails):
            raise images.ReleaseError("Simulated unavailable AWS response")
        if service == "sts" and action == "get-caller-identity":
            return {"Account": self.release["accountId"]}
        if action == "describe-task-definition":
            return {"taskDefinition": self.definition}
        if action == "describe-subnets":
            return {
                "Subnets": [
                    {
                        "SubnetId": subnet,
                        "VpcId": self.vpc,
                        "OwnerId": self.owner,
                        "MapPublicIpOnLaunch": self.public_subnet,
                    }
                    for subnet in self.target["subnets"]
                ]
            }
        if action == "describe-security-groups":
            return {
                "SecurityGroups": [
                    {
                        "GroupId": self.target["groups"][0],
                        "VpcId": self.vpc,
                        "OwnerId": self.owner,
                        "GroupName": self.target["name"] + "-api",
                    }
                ]
            }
        if action == "run-task":
            request = json.loads(arguments[arguments.index("--cli-input-json") + 1])
            self.started_by = request["startedBy"]
            return {
                "tasks": [self.task("PROVISIONING")],
                "failures": [{"reason": "unavailable"}] if self.launch_failure else [],
            }
        if action == "describe-tasks":
            if self.missing:
                self.missing -= 1
                return {
                    "tasks": [],
                    "failures": [{"arn": self.arn, "reason": "MISSING"}],
                }
            state = self.states.pop(0) if len(self.states) > 1 else self.states[0]
            return {"tasks": [self.task(state)], "failures": []}
        if action == "stop-task":
            return {"task": self.task("STOPPING")}
        raise AssertionError(f"Unexpected migration operation: {service} {action}")


def fixture():
    release = images.metadata(
        "123456789012", "eu-west-2", "closetos", "dev", "c" * 40, "9876-2"
    )
    manifest = {
        **release,
        "images": {
            service: {
                **release,
                "service": service,
                "localImageId": "sha256:" + "a" * 64,
                "imageDigest": "sha256:" + "b" * 64,
                "imageReference": images.repository_uri(release, service)
                + "@sha256:"
                + "b" * 64,
            }
            for service in images.SERVICES
        },
    }
    prefix = "arn:aws:ecs:eu-west-2:123456789012:"
    values = {
        "ecs_cluster_arn": prefix + "cluster/closetos-dev",
        "migration_task_definition_arn": prefix
        + "task-definition/closetos-dev-migration:7",
        "migration_network": {
            "subnets": ["subnet-00000001", "subnet-00000002"],
            "security_groups": ["sg-00000001"],
            "assign_public_ip": False,
        },
        "vpc_id": "vpc-00000001",
        "database_address": "database.eu-west-2.rds.amazonaws.com",
        "database_secret_arn": "arn:aws:secretsmanager:eu-west-2:123456789012:secret:rds!db-example",
        "application_secret_arns": {
            "database-app": "arn:aws:secretsmanager:eu-west-2:123456789012:secret:closetos-dev/database-app-example"
        },
    }
    return release, manifest, {key: {"value": value} for key, value in values.items()}


class ReleaseMigrationTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.result = Path(self.directory.name) / "migration.json"
        self.release, self.manifest, self.outputs = fixture()
        self.tools = MigrationTools(self.release, self.manifest, self.outputs)

    def run_migration(self, timeout=30):
        with (
            patch.object(migration, "api", self.tools),
            patch.object(migration.time, "sleep", self.tools.sleep),
            patch.object(migration.time, "monotonic", lambda: self.tools.tick),
        ):
            return migration.run_migration(
                self.release, self.manifest, self.outputs, self.result, timeout
            )

    def actions(self):
        return [action for _, action, _ in self.tools.calls]

    def test_success_requires_a_confirmed_exit_of_the_exact_release_image(self):
        result = self.run_migration()
        self.assertEqual(result["status"], "SUCCEEDED")
        self.assertEqual(
            result["apiImage"], self.manifest["images"]["api"]["imageReference"]
        )
        self.assertEqual(result, json.loads(self.result.read_text()))
        self.assertEqual(result["taskArn"], self.tools.arn)
        self.assertEqual(self.actions().count("run-task"), 1)
        self.assertEqual(self.actions().count("describe-tasks"), 3)
        self.assertNotIn("stop-task", self.actions())
        arguments = next(
            arguments
            for _, action, arguments in self.tools.calls
            if action == "run-task"
        )
        run_request = json.loads(arguments[arguments.index("--cli-input-json") + 1])
        network = run_request["networkConfiguration"]
        self.assertEqual(network["awsvpcConfiguration"]["assignPublicIp"], "DISABLED")
        self.assertFalse(run_request["enableExecuteCommand"])
        self.assertNotIn("overrides", run_request)
        request = json.loads(
            self.result.with_name("migration-request.json").read_text()
        )
        self.assertEqual(len(request["clientToken"]), 64)
        self.assertEqual(request, run_request)

    def test_missing_observations_wait_for_the_same_task_without_restarting(self):
        self.tools.missing = 2
        self.run_migration()
        self.assertEqual(self.actions().count("run-task"), 1)
        self.assertEqual(self.actions().count("describe-tasks"), 5)

    def test_nonzero_missing_and_boolean_exit_codes_never_open_the_gate(self):
        for value in [1, 143, None, False]:
            with self.subTest(exit_code=value):
                self.tools = MigrationTools(self.release, self.manifest, self.outputs)
                self.tools.exit_code = value
                self.result = Path(self.directory.name) / f"failed-{value!s}.json"
                with self.assertRaisesRegex(
                    images.ReleaseError, "successful container exit"
                ):
                    self.run_migration()
                self.assertFalse(self.result.exists())
                self.assertNotIn("stop-task", self.actions())

    def test_task_start_failure_never_counts_as_a_successful_exit(self):
        self.tools.stop_code = "TaskFailedToStart"
        with self.assertRaisesRegex(images.ReleaseError, "finish normally"):
            self.run_migration()
        self.assertFalse(self.result.exists())

    def test_timeout_requests_a_stop_and_retains_the_observed_task_handle(self):
        self.tools.states = ["RUNNING"]
        with self.assertRaisesRegex(images.ReleaseError, "deadline expired"):
            self.run_migration()
        self.assertEqual(self.actions().count("run-task"), 1)
        self.assertEqual(self.actions().count("stop-task"), 1)
        self.assertFalse(self.result.exists())
        task_file = self.result.with_name("migration-task.json")
        self.assertEqual(json.loads(task_file.read_text())["taskArn"], self.tools.arn)

    def test_unavailable_observation_attempts_cleanup_and_never_reports_success(self):
        self.tools.fail = "describe-tasks"
        with self.assertRaises(images.ReleaseError):
            self.run_migration()
        self.assertEqual(self.actions()[-1], "stop-task")
        self.assertFalse(self.result.exists())

    def test_failed_cleanup_explicitly_leaves_completion_unconfirmed(self):
        self.tools.fail = "describe-tasks"
        self.tools.cleanup_fails = True
        with self.assertRaisesRegex(
            images.ReleaseError, "completion and cleanup are unconfirmed"
        ):
            self.run_migration()
        self.assertFalse(self.result.exists())

    def test_a_partial_launch_response_is_cleaned_up_without_passing(self):
        self.tools.launch_failure = True
        with self.assertRaisesRegex(images.ReleaseError, "launch failure"):
            self.run_migration()
        self.assertEqual(self.actions()[-1], "stop-task")
        self.assertFalse(self.result.exists())

    def test_ambiguous_launch_retains_its_idempotency_token(self):
        self.tools.fail = "run-task"
        with self.assertRaises(images.ReleaseError):
            self.run_migration()
        self.assertTrue(self.result.with_name("migration-request.json").exists())
        self.assertNotIn("stop-task", self.actions())
        self.assertFalse(self.result.exists())

    def test_same_release_reuses_the_same_client_token_at_a_fresh_destination(self):
        self.run_migration()
        first = json.loads(self.result.with_name("migration-request.json").read_text())
        self.result = Path(self.directory.name) / "retry.json"
        self.tools = MigrationTools(self.release, self.manifest, self.outputs)
        self.run_migration()
        second = json.loads(self.result.with_name("retry-request.json").read_text())
        self.assertEqual(first["clientToken"], second["clientToken"])

    def test_a_different_commit_or_environment_is_rejected_before_aws(self):
        self.manifest["sourceCommit"] = "d" * 40
        with self.assertRaises(images.ReleaseError):
            self.run_migration()
        self.assertEqual(self.tools.calls, [])

    def test_wrong_image_entrypoint_or_roles_prevent_launch(self):
        definition = copy.deepcopy(self.tools.definition)
        for field, value in [
            ("image", "latest"),
            ("command", ["start-server"]),
            ("readonlyRootFilesystem", False),
            ("entryPoint", ["bash"]),
            ("environment", [{"name": "DATABASE_PASSWORD", "value": "plaintext"}]),
        ]:
            with self.subTest(field=field):
                self.tools = MigrationTools(self.release, self.manifest, self.outputs)
                self.tools.definition["containerDefinitions"][0][field] = value
                with self.assertRaises(images.ReleaseError):
                    self.run_migration()
                self.assertNotIn("run-task", self.actions())
        self.tools.definition = definition
        self.tools.definition["taskRoleArn"] = "arn:aws:iam::123456789012:role/api-task"
        with self.assertRaises(images.ReleaseError):
            self.run_migration()

    def test_secret_and_environment_order_does_not_change_the_task_contract(self):
        container = self.tools.definition["containerDefinitions"][0]
        container["environment"].reverse()
        container["secrets"].reverse()
        self.run_migration()

    def test_private_subnets_must_be_in_this_accounts_application_vpc(self):
        for field, value in [
            ("owner", "999999999999"),
            ("vpc", "vpc-00000002"),
            ("public_subnet", True),
        ]:
            with self.subTest(field=field):
                self.tools = MigrationTools(self.release, self.manifest, self.outputs)
                setattr(self.tools, field, value)
                with self.assertRaises(images.ReleaseError):
                    self.run_migration()
                self.assertNotIn("run-task", self.actions())

    def test_existing_results_cannot_be_mistaken_for_this_migration(self):
        self.result.write_text('{"status":"SUCCEEDED"}')
        with self.assertRaisesRegex(images.ReleaseError, "already exist"):
            self.run_migration()
        self.assertEqual(self.tools.calls, [])

    def test_public_or_another_environments_outputs_are_rejected_without_aws(self):
        for key, value in [
            (
                "ecs_cluster_arn",
                "arn:aws:ecs:eu-west-2:123456789012:cluster/closetos-prod",
            ),
            ("migration_task_definition_arn", "latest"),
            ("migration_network", {"assign_public_ip": True}),
            (
                "migration_network",
                {
                    "assign_public_ip": False,
                    "subnets": [{}],
                    "security_groups": ["sg-00000001"],
                },
            ),
        ]:
            with self.subTest(key=key, value=value):
                altered = copy.deepcopy(self.outputs)
                altered[key]["value"] = value
                with self.assertRaises(images.ReleaseError):
                    migration.context(altered, self.release)

    def test_interruption_requests_cleanup_of_the_observed_task(self):
        original = self.tools

        def interrupted(region, service, action, *arguments):
            if action == "describe-tasks":
                migration.interrupted(None, None)
            return original(region, service, action, *arguments)

        with (
            patch.object(migration, "api", interrupted),
            self.assertRaisesRegex(images.ReleaseError, "interrupted"),
        ):
            migration.run_migration(
                self.release, self.manifest, self.outputs, self.result, 30
            )
        self.assertEqual(self.actions()[-1], "stop-task")
        self.assertFalse(self.result.exists())

    def test_unrelated_observation_never_opens_the_gate_or_stops_a_foreign_task(self):
        original = self.tools
        foreign = "arn:aws:ecs:eu-west-2:123456789012:task/closetos-prod/" + "d" * 32

        def unrelated(region, service, action, *arguments):
            result = original(region, service, action, *arguments)
            if action == "describe-tasks":
                result["tasks"][0]["taskArn"] = foreign
            return result

        with (
            patch.object(migration, "api", unrelated),
            self.assertRaises(images.ReleaseError),
        ):
            migration.run_migration(
                self.release, self.manifest, self.outputs, self.result, 30
            )
        stopped = [
            arguments
            for _, action, arguments in self.tools.calls
            if action == "stop-task"
        ]
        self.assertEqual(len(stopped), 1)
        self.assertIn(self.tools.arn, stopped[0])
        self.assertNotIn(foreign, stopped[0])

    def test_actual_cli_checks_and_runs_through_local_aws_processes(self):
        directory = Path(self.directory.name)
        scripts = Path(__file__).resolve().parent
        manifest_file = directory / "manifest.json"
        infrastructure_file = directory / "infrastructure.json"
        manifest_file.write_text(json.dumps(self.manifest))
        infrastructure_file.write_text(json.dumps(self.outputs))
        arguments = [
            sys.executable,
            str(scripts / "release_migration.py"),
            "check",
            "--account",
            self.release["accountId"],
            "--region",
            self.release["region"],
            "--source",
            self.release["sourceCommit"],
            "--publication",
            self.release["publicationId"],
            "--manifest",
            str(manifest_file),
            "--infrastructure",
            str(infrastructure_file),
            "--destination",
            str(self.result),
        ]
        checked = subprocess.run(
            arguments, capture_output=True, text=True, check=False, timeout=10
        )
        self.assertEqual(checked.returncode, 0, checked.stderr)
        self.assertFalse(self.result.exists())
        executables = directory / "bin"
        executables.mkdir()
        stub = executables / "aws"
        state_file = directory / "aws-state.json"
        stub.write_text(
            f"#!{sys.executable}\n"
            "import json, sys\nfrom pathlib import Path\n"
            f"sys.path.insert(0, {str(scripts)!r})\n"
            "from test_release_migration import fixture, MigrationTools\n"
            "release, manifest, outputs = fixture()\n"
            "tools = MigrationTools(release, manifest, outputs)\n"
            f"state = Path({str(state_file)!r})\n"
            "tools.started_by = json.loads(state.read_text()) if state.exists() else None\n"
            "tools.states = ['STOPPED']\n"
            "response = tools(release['region'], *sys.argv[1:])\n"
            "if sys.argv[2] == 'run-task': state.write_text(json.dumps(tools.started_by))\n"
            "print(json.dumps(response))\n"
        )
        stub.chmod(0o755)
        environment = {
            key: value
            for key, value in os.environ.items()
            if not key.startswith("AWS_")
        }
        environment["PATH"] = (
            str(executables) + os.pathsep + environment.get("PATH", "")
        )
        arguments[2] = "run"
        result = subprocess.run(
            arguments,
            capture_output=True,
            text=True,
            check=False,
            env=environment,
            timeout=30,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(self.result.read_text())["status"], "SUCCEEDED")
        self.assertNotIn("DATABASE_PASSWORD", result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
