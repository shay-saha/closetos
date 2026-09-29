import copy
import json
import os
import subprocess
import sys
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from unittest.mock import patch

import release_images as images
import release_migration as migration
import release_rollout as rollout
from test_release_migration import fixture as migration_fixture


def workflow(cluster, arn):
    return {
        "StartAt": "RunMediaTransform",
        "States": {
            key: {
                "Type": "Task",
                "Resource": "arn:aws:states:::ecs:runTask.sync",
                "Parameters": {
                    "Cluster": cluster,
                    "TaskDefinition": arn,
                    "LaunchType": "FARGATE",
                    "NetworkConfiguration": {
                        "AwsvpcConfiguration": {
                            "AssignPublicIp": "DISABLED",
                            "Subnets": ["subnet-00000001"],
                            "SecurityGroups": ["sg-00000001"],
                        }
                    },
                    "Overrides": {
                        "ContainerOverrides": [
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
                        ]
                    },
                },
                **(
                    {"Next": "RunAIEnrichment"}
                    if command == "transform"
                    else {"End": True}
                ),
            }
            for key, command in [
                ("RunMediaTransform", "transform"),
                ("RunAIEnrichment", "enrich"),
            ]
        },
    }


def fixture():
    release, manifest, outputs = migration_fixture()
    prefix = "arn:aws:ecs:eu-west-2:123456789012:task-definition/closetos-dev-"
    secrets = outputs["application_secret_arns"]["value"]
    for key in ["media-signing", "web-session"]:
        secrets[key] = (
            "arn:aws:secretsmanager:eu-west-2:123456789012:secret:closetos-dev/"
            + key
            + "-example"
        )
    cluster = outputs["ecs_cluster_arn"]["value"]
    outputs.update(
        {
            key: {"value": value}
            for key, value in {
                "service_names": {key: "closetos-dev-" + key for key in ["api", "web"]},
                "service_task_definitions": {
                    key: prefix + key + ":8" for key in ["api", "web"]
                },
                "processing_state_machine_arn": "arn:aws:states:eu-west-2:123456789012:stateMachine:closetos-dev-media",
                "processing_workflow_definition": workflow(cluster, prefix + "media:8"),
                "application_url": "https://closet.example.test",
            }.items()
        }
    )
    receipt = {
        **release,
        "status": "SUCCEEDED",
        "exitCode": 0,
        "taskArn": cluster.replace(":cluster/", ":task/") + "/" + "a" * 32,
        "taskDefinitionArn": outputs["migration_task_definition_arn"]["value"],
        "apiImage": manifest["images"]["api"]["imageReference"],
        "runtimeImageDigest": "sha256:" + "d" * 64,
    }
    return release, manifest, outputs, receipt


class RolloutTools:
    def __init__(self, release, manifest, outputs, receipt):
        self.release, self.manifest, self.receipt = release, manifest, receipt
        self.target = rollout.context(outputs, release)
        self.calls, self.trace = [], []
        self.tick, self.deployment = 0, 0
        self.count = 3
        self.bad_migration = None
        self.account = release["accountId"]
        self.fail_forward = None
        self.fail_rollback = None
        self.unhealthy = None
        self.auto_rollback = False
        self.pending = False
        self.stale_workflow = 0
        self.workflow_reads = 0
        self.observation_fails = False
        self.services = {
            key: self.service(
                key, self.target["definitions"][key].replace(":8", ":7"), "old-" + key
            )
            for key in ["api", "web"]
        }
        self.workflow = workflow(
            self.target["cluster"],
            self.target["definitions"]["media-worker"].replace(":8", ":7"),
        )
        self.old_workflow = copy.deepcopy(self.workflow)
        self.definitions = {}
        for service, arn in self.target["definitions"].items():
            for revision in [7, 8]:
                reference = arn.rsplit(":", 1)[0] + ":" + str(revision)
                role = f"arn:aws:iam::{release['accountId']}:role/{self.target['name']}-{service}-"
                secrets = {
                    "api": {
                        "DATABASE_PASSWORD": self.target["secrets"]["database-app"],
                        "CLOUDFRONT_PRIVATE_KEY": self.target["secrets"][
                            "media-signing"
                        ],
                    },
                    "web": {"NEXTAUTH_SECRET": self.target["secrets"]["web-session"]},
                    "media-worker": {},
                }[service]
                env = {
                    "api": {
                        "SPRING_FLYWAY_ENABLED": "false",
                        "DATABASE_USERNAME": "closetos_app",
                        "DATABASE_URL": self.target["databaseUrl"],
                    },
                    "web": {
                        "NEXTAUTH_URL": self.target["origin"],
                        "API_URL": f"http://api.{self.target['name']}.internal:8080",
                    },
                    "media-worker": {},
                }[service]
                image = manifest["images"][service]["imageReference"]
                self.definitions[reference] = {
                    "taskDefinitionArn": reference,
                    "status": "ACTIVE",
                    "requiresCompatibilities": ["FARGATE"],
                    "networkMode": "awsvpc",
                    "runtimePlatform": {
                        "cpuArchitecture": "X86_64",
                        "operatingSystemFamily": "LINUX",
                    },
                    "taskRoleArn": role + "task",
                    "executionRoleArn": role + "execution",
                    "containerDefinitions": [
                        {
                            "name": service,
                            "essential": True,
                            "readonlyRootFilesystem": True,
                            "user": {
                                "api": "closetos",
                                "web": "node",
                                "media-worker": "10001",
                            }[service],
                            "image": image
                            if revision == 8
                            else image.split("@")[0] + "@sha256:" + "e" * 64,
                            "environment": [
                                {"name": key, "value": value}
                                for key, value in env.items()
                            ],
                            "secrets": [
                                {"name": key, "valueFrom": value}
                                for key, value in secrets.items()
                            ],
                        }
                    ],
                }

    def service(self, key, arn, deployment):
        return {
            "clusterArn": self.target["cluster"],
            "serviceName": self.target["services"][key],
            "serviceArn": self.target["cluster"].replace(":cluster/", ":service/")
            + "/"
            + self.target["services"][key],
            "status": "ACTIVE",
            "launchType": "FARGATE",
            "deploymentController": {"type": "ECS"},
            "enableExecuteCommand": False,
            "networkConfiguration": {
                "awsvpcConfiguration": {"assignPublicIp": "DISABLED"}
            },
            "taskDefinition": arn,
            "desiredCount": self.count,
            "runningCount": self.count,
            "pendingCount": 0,
            "deployments": [
                {
                    "id": deployment,
                    "status": "PRIMARY",
                    "taskDefinition": arn,
                    "rolloutState": "COMPLETED",
                }
            ],
        }

    def sleep(self, seconds):
        self.tick += seconds

    def __call__(self, region, service, action, *arguments):
        self.calls.append((service, action, list(arguments)))
        result = self.respond(region, service, action, *arguments)
        self.trace.append(
            {
                "arguments": [
                    service,
                    action,
                    *arguments,
                    "--cli-connect-timeout",
                    "5",
                    "--cli-read-timeout",
                    "20",
                    "--region",
                    region,
                    "--no-cli-pager",
                    "--output",
                    "json",
                ],
                "response": copy.deepcopy(result),
            }
        )
        return result

    def respond(self, region, service, action, *arguments):
        if action == "get-caller-identity":
            return {"Account": self.account}
        if action == "describe-tasks":
            _, started_by = migration.request_identity(self.release, self.target)
            task = {
                "taskArn": self.receipt["taskArn"],
                "clusterArn": self.target["cluster"],
                "taskDefinitionArn": self.target["definition"],
                "startedBy": started_by,
                "lastStatus": "STOPPED",
                "stopCode": "EssentialContainerExited",
                "containers": [
                    {
                        "name": "migration",
                        "lastStatus": "STOPPED",
                        "exitCode": 0,
                        "image": self.receipt["apiImage"],
                        "imageDigest": self.receipt["runtimeImageDigest"],
                    }
                ],
            }
            if self.bad_migration:
                self.bad_migration(task)
            return {"tasks": [task], "failures": []}
        if action == "describe-task-definition":
            return {"taskDefinition": copy.deepcopy(self.definitions[arguments[1]])}
        if action == "describe-services":
            key = arguments[3].rsplit("-", 1)[1]
            observed = copy.deepcopy(self.services[key])
            if self.observation_fails and self.deployment == 1:
                raise images.ReleaseError("Observation unavailable")
            if observed["taskDefinition"].endswith(":8"):
                if self.auto_rollback:
                    observed = self.service(
                        key,
                        observed["taskDefinition"].replace(":8", ":7"),
                        "automatic-rollback",
                    )
                elif self.unhealthy == key:
                    observed["deployments"][0]["rolloutState"] = "FAILED"
                elif self.pending:
                    observed["deployments"][0]["rolloutState"] = "IN_PROGRESS"
                    observed["pendingCount"] = 1
                else:
                    observed["desiredCount"] = observed["runningCount"] = self.count
            return {"services": [observed], "failures": []}
        if action == "update-service":
            request = json.loads(arguments[1])
            key = request["service"].rsplit("-", 1)[1]
            self.deployment += 1
            observed = self.service(
                key, request["taskDefinition"], "deployment-" + str(self.deployment)
            )
            if "desiredCount" in request:
                observed["desiredCount"] = observed["runningCount"] = request[
                    "desiredCount"
                ]
                self.count = request["desiredCount"]
            self.services[key] = observed
            if (
                request["taskDefinition"].endswith(":8") and self.fail_forward == key
            ) or (
                request["taskDefinition"].endswith(":7") and self.fail_rollback == key
            ):
                # The remote change happened even though the caller did not receive its response.
                return {"service": {}}
            return {"service": copy.deepcopy(observed)}
        if action == "describe-state-machine":
            self.workflow_reads += 1
            definition = (
                self.old_workflow
                if self.workflow_reads <= self.stale_workflow
                else self.workflow
            )
            return {
                "stateMachineArn": self.target["machine"],
                "roleArn": f"arn:aws:iam::{self.release['accountId']}:role/{self.target['name']}-media-workflow",
                "status": "ACTIVE",
                "type": "STANDARD",
                "definition": json.dumps(definition),
            }
        if action == "update-state-machine":
            self.workflow = json.loads(arguments[3])
            return {"revisionId": "revision-2"}
        raise AssertionError(f"Unexpected rollout action: {service} {action}")


class ReleaseRolloutTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.result = Path(self.directory.name) / "rollout.json"
        self.release, self.manifest, self.outputs, self.receipt = fixture()
        self.tools = RolloutTools(
            self.release, self.manifest, self.outputs, self.receipt
        )

    def run_rollout(self, smoke=None):
        with (
            patch.object(rollout, "api", self.tools),
            patch.object(rollout.time, "monotonic", lambda: self.tools.tick),
            patch.object(rollout.time, "sleep", self.tools.sleep),
            patch.object(rollout, "smoke", smoke or (lambda origin: None)),
        ):
            return rollout.rollout(
                self.release, self.manifest, self.outputs, self.receipt, self.result, 30
            )

    def updates(self):
        return [
            json.loads(args[1])
            for _, action, args in self.tools.calls
            if action == "update-service"
        ]

    def test_success_preserves_autoscaling_and_saves_previous_revisions_before_changes(
        self,
    ):
        def smoke(origin):
            self.assertEqual(origin, "https://closet.example.test")
            previous = json.loads(
                self.result.with_name("rollout-previous.json").read_text()
            )
            self.assertEqual(previous["services"]["api"]["desiredCount"], 3)
            self.assertTrue(
                previous["services"]["web"]["taskDefinition"].endswith(":7")
            )
            self.assertFalse(self.result.exists())

        result = self.run_rollout(smoke)
        self.assertEqual(result["status"], "SUCCEEDED")
        self.assertEqual(result, json.loads(self.result.read_text()))
        self.assertEqual(len(self.updates()), 2)
        self.assertTrue(all("desiredCount" not in update for update in self.updates()))
        self.assertTrue(
            all(update["enableExecuteCommand"] is False for update in self.updates())
        )
        self.assertEqual(self.tools.workflow, self.tools.target["workflow"])
        self.assertEqual(self.result.stat().st_mode & 0o777, 0o600)

    def test_initial_release_starts_one_development_task_after_migration(self):
        self.tools.count = 0
        for service in self.tools.services.values():
            service["desiredCount"] = service["runningCount"] = 0
        self.run_rollout()
        self.assertEqual([update["desiredCount"] for update in self.updates()], [1, 1])
        actions = [action for _, action, _ in self.tools.calls]
        self.assertLess(
            actions.index("describe-tasks"), actions.index("update-service")
        )

    def test_old_or_unsuccessful_migration_receipts_never_change_services(self):
        for key, value in [
            ("sourceCommit", "f" * 40),
            ("publicationId", "9999-1"),
            ("environment", "prod"),
            ("exitCode", False),
            ("exitCode", 1),
            ("status", "RUNNING"),
            ("apiImage", "latest"),
            ("taskDefinitionArn", "latest"),
            ("taskArn", "foreign"),
        ]:
            with self.subTest(key=key, value=value):
                altered = {**self.receipt, key: value}
                with (
                    patch.object(rollout, "api", self.tools),
                    self.assertRaises(images.ReleaseError),
                ):
                    rollout.rollout(
                        self.release,
                        self.manifest,
                        self.outputs,
                        altered,
                        self.result,
                        30,
                    )
                self.assertEqual(self.updates(), [])

    def test_initial_production_release_starts_two_tasks_per_service(self):
        self.release, self.manifest, self.outputs, self.receipt = json.loads(
            json.dumps([self.release, self.manifest, self.outputs, self.receipt])
            .replace("closetos-dev", "closetos-prod")
            .replace('"environment": "dev"', '"environment": "prod"')
        )
        self.tools = RolloutTools(
            self.release, self.manifest, self.outputs, self.receipt
        )
        self.tools.count = 0
        for service in self.tools.services.values():
            service["desiredCount"] = service["runningCount"] = 0
        self.run_rollout()
        self.assertEqual([update["desiredCount"] for update in self.updates()], [2, 2])

    def test_migration_is_reobserved_in_ecs_instead_of_trusting_a_success_file(self):
        for mutate in [
            lambda task: task.update(lastStatus="RUNNING"),
            lambda task: task.update(startedBy="another-release"),
            lambda task: task["containers"][0].update(exitCode=False),
            lambda task: task["containers"][0].update(imageDigest="sha256:" + "f" * 64),
            lambda task: task.update(stopCode="TaskFailedToStart"),
        ]:
            self.tools.bad_migration = mutate
            with self.assertRaises(images.ReleaseError):
                self.run_rollout()
            self.assertEqual(self.updates(), [])

    def test_wrong_account_cannot_even_inspect_the_migration_task(self):
        self.tools.account = "999999999999"
        with self.assertRaises(images.ReleaseError):
            self.run_rollout()
        self.assertEqual(len(self.tools.calls), 1)

    def test_new_task_images_roles_secrets_and_runtime_settings_are_verified(self):
        for service, mutate in [
            (
                "api",
                lambda task: task["containerDefinitions"][0].update(image="latest"),
            ),
            (
                "api",
                lambda task: task.update(
                    taskRoleArn="arn:aws:iam::123456789012:role/admin"
                ),
            ),
            (
                "api",
                lambda task: task["containerDefinitions"][0]["secrets"][0].update(
                    valueFrom=self.tools.target["masterSecret"]
                ),
            ),
            (
                "api",
                lambda task: task["containerDefinitions"][0]["environment"][0].update(
                    value="true"
                ),
            ),
            ("web", lambda task: task["containerDefinitions"][0].update(user="root")),
            (
                "web",
                lambda task: task["containerDefinitions"][0]["environment"][1].update(
                    value="https://external.test"
                ),
            ),
            (
                "media-worker",
                lambda task: task["containerDefinitions"][0].update(
                    image=self.manifest["images"]["api"]["imageReference"]
                ),
            ),
        ]:
            with self.subTest(service=service):
                self.tools = RolloutTools(
                    self.release, self.manifest, self.outputs, self.receipt
                )
                mutate(
                    self.tools.definitions[self.tools.target["definitions"][service]]
                )
                with self.assertRaises(images.ReleaseError):
                    self.run_rollout()
                self.assertEqual(self.updates(), [])

    def test_previous_revisions_must_remain_active_for_rollback(self):
        arn = self.tools.services["api"]["taskDefinition"]
        self.tools.definitions[arn]["status"] = "INACTIVE"
        with self.assertRaises(images.ReleaseError):
            self.run_rollout()
        self.assertEqual(self.updates(), [])

    def test_unfinished_or_foreign_service_deployments_are_rejected(self):
        for key, value in [
            ("clusterArn", "foreign"),
            ("enableExecuteCommand", True),
            ("pendingCount", 1),
            ("desiredCount", True),
            ("taskDefinition", "latest"),
        ]:
            with self.subTest(key=key):
                self.tools = RolloutTools(
                    self.release, self.manifest, self.outputs, self.receipt
                )
                self.tools.services["api"][key] = value
                with self.assertRaises(images.ReleaseError):
                    self.run_rollout()
                self.assertEqual(self.updates(), [])

    def test_unhealthy_api_does_not_roll_web_or_processing_forward(self):
        self.tools.unhealthy = "api"
        with self.assertRaisesRegex(images.ReleaseError, "restored"):
            self.run_rollout()
        updates = self.updates()
        self.assertEqual(len(updates), 2)
        self.assertTrue(updates[1]["taskDefinition"].endswith(":7"))
        self.assertEqual(json.loads(self.result.read_text())["status"], "ROLLED_BACK")
        self.assertNotIn(
            "update-state-machine", [action for _, action, _ in self.tools.calls]
        )

    def test_automatic_rollback_to_an_old_healthy_revision_never_reports_success(self):
        self.tools.auto_rollback = True
        with self.assertRaises(images.ReleaseError):
            self.run_rollout()
        self.assertEqual(self.tools.tick, 30)
        self.assertEqual(json.loads(self.result.read_text())["status"], "ROLLED_BACK")

    def test_observation_timeout_and_errors_restore_the_attempted_revision(self):
        for field in ["pending", "observation_fails"]:
            with self.subTest(field=field):
                self.tools = RolloutTools(
                    self.release, self.manifest, self.outputs, self.receipt
                )
                setattr(self.tools, field, True)
                destination = self.result.with_name(field + ".json")
                with (
                    patch.object(rollout, "api", self.tools),
                    patch.object(rollout.time, "monotonic", lambda: self.tools.tick),
                    patch.object(rollout.time, "sleep", self.tools.sleep),
                    self.assertRaises(images.ReleaseError),
                ):
                    rollout.rollout(
                        self.release,
                        self.manifest,
                        self.outputs,
                        self.receipt,
                        destination,
                        30,
                    )
                self.assertEqual(
                    json.loads(destination.read_text())["status"], "ROLLED_BACK"
                )
                self.assertTrue(self.updates()[-1]["taskDefinition"].endswith(":7"))

    def test_ambiguous_web_update_restores_both_services(self):
        self.tools.fail_forward = "web"
        with self.assertRaisesRegex(images.ReleaseError, "restored"):
            self.run_rollout()
        self.assertEqual(
            [request["service"] for request in self.updates()],
            [
                "closetos-dev-api",
                "closetos-dev-web",
                "closetos-dev-web",
                "closetos-dev-api",
            ],
        )
        self.assertTrue(
            all(
                service["taskDefinition"].endswith(":7")
                for service in self.tools.services.values()
            )
        )

    def test_failed_smoke_checks_restore_services_and_the_previous_worker_workflow(
        self,
    ):
        def failed(origin):
            raise images.ReleaseError("Smoke check failed")

        with self.assertRaisesRegex(images.ReleaseError, "restored"):
            self.run_rollout(failed)
        self.assertEqual(self.tools.workflow, self.tools.old_workflow)
        self.assertEqual(json.loads(self.result.read_text())["status"], "ROLLED_BACK")

    def test_failed_first_release_returns_services_to_zero_tasks(self):
        self.tools.count = 0
        for service in self.tools.services.values():
            service["desiredCount"] = service["runningCount"] = 0
        self.tools.fail_forward = "web"
        with self.assertRaises(images.ReleaseError):
            self.run_rollout()
        self.assertEqual(
            [update["desiredCount"] for update in self.updates()], [1, 1, 0, 0]
        )

    def test_an_api_regression_after_web_rollout_cannot_publish_success(self):
        def regression(_origin):
            arn = self.tools.services["api"]["taskDefinition"].replace(":8", ":7")
            self.tools.services["api"] = self.tools.service(
                "api", arn, "another-deployment"
            )

        with self.assertRaisesRegex(images.ReleaseError, "restored"):
            self.run_rollout(regression)
        self.assertEqual(json.loads(self.result.read_text())["status"], "ROLLED_BACK")

    def test_receipt_write_failure_still_restores_the_remote_release(self):
        original = rollout.write_json

        def unavailable(path, value):
            if value.get("status") == "SUCCEEDED":
                raise OSError("Storage unavailable")
            return original(path, value)

        with (
            patch.object(rollout, "write_json", unavailable),
            self.assertRaisesRegex(images.ReleaseError, "restored"),
        ):
            self.run_rollout()
        self.assertEqual(self.tools.workflow, self.tools.old_workflow)
        self.assertEqual(json.loads(self.result.read_text())["status"], "ROLLED_BACK")

    def test_unconfirmed_rollback_is_recorded_and_other_services_are_still_restored(
        self,
    ):
        self.tools.fail_forward = "web"
        self.tools.fail_rollback = "web"
        with self.assertRaisesRegex(images.ReleaseError, "unconfirmed"):
            self.run_rollout()
        self.assertEqual(
            json.loads(self.result.read_text())["status"], "ROLLBACK_UNCONFIRMED"
        )
        self.assertEqual(self.updates()[-1]["service"], "closetos-dev-api")

    def test_interruption_during_an_update_still_restores_that_service(self):
        original = self.tools

        def interrupted(region, service, action, *arguments):
            response = original(region, service, action, *arguments)
            if action == "update-service" and original.deployment == 1:
                raise KeyboardInterrupt()
            return response

        with (
            patch.object(rollout, "api", interrupted),
            self.assertRaisesRegex(images.ReleaseError, "restored"),
        ):
            rollout.rollout(
                self.release, self.manifest, self.outputs, self.receipt, self.result, 30
            )
        self.assertTrue(self.updates()[-1]["taskDefinition"].endswith(":7"))

    def test_workflow_readback_can_lag_without_another_deployment(self):
        self.tools.stale_workflow = 3
        self.run_rollout()
        self.assertEqual(self.tools.tick, 10)
        self.assertEqual(len(self.updates()), 2)
        self.assertEqual(
            [action for _, action, _ in self.tools.calls].count("update-state-machine"),
            1,
        )

    def test_mixed_manifest_and_unsafe_outputs_are_rejected_without_credentials(self):
        for key, value in [
            ("application_url", "http://closet.example.test"),
            ("application_url", "https://closet.example.test/redirect"),
            ("application_url", "https://user:password@closet.example.test"),
            ("processing_state_machine_arn", "foreign"),
            ("service_names", {"api": "closetos-prod-api", "web": "closetos-dev-web"}),
        ]:
            with self.subTest(key=key, value=value):
                altered = copy.deepcopy(self.outputs)
                altered[key]["value"] = value
                with (
                    patch.object(rollout, "api", self.tools),
                    self.assertRaises(images.ReleaseError),
                ):
                    rollout.rollout(
                        self.release,
                        self.manifest,
                        altered,
                        self.receipt,
                        self.result,
                        30,
                    )
                self.assertEqual(self.tools.calls, [])
        self.manifest["images"]["web"]["sourceCommit"] = "f" * 40
        with self.assertRaises(images.ReleaseError):
            self.run_rollout()
        self.assertEqual(self.tools.calls, [])

    def test_previous_state_file_is_never_overwritten_by_another_release(self):
        self.result.with_name("rollout-previous.json").write_text("existing")
        with self.assertRaisesRegex(images.ReleaseError, "fresh"):
            self.run_rollout()
        self.assertEqual(self.tools.calls, [])

    def test_public_smokes_check_signin_authentication_and_same_origin_https_redirect(
        self,
    ):
        responses = [
            (200, {"Content-Type": "text/html"}, b"CLOSET Open your wardrobe"),
            (401, {"Content-Type": "application/problem+json"}, b"{}"),
            (401, {"Content-Type": "application/json"}, b"{}"),
            (301, {"Location": "https://closet.example.test:443/"}, b""),
        ]
        with patch.object(rollout, "http_get", side_effect=responses) as http:
            rollout.smoke("https://closet.example.test")
        self.assertEqual(
            [call.args[0] for call in http.call_args_list],
            [
                "https://closet.example.test/signin",
                "https://closet.example.test/api/v1/me",
                "https://closet.example.test/api/backend/me",
                "http://closet.example.test/",
            ],
        )
        for index, response in [
            (0, (200, {"Content-Type": "text/html"}, b"unrelated page")),
            (1, (200, {"Content-Type": "application/json"}, b"{}")),
            (2, (302, {"Location": "https://other.test"}, b"")),
            (3, (301, {"Location": "https://other.test/"}, b"")),
        ]:
            altered = list(responses)
            altered[index] = response
            with (
                patch.object(rollout, "http_get", side_effect=altered),
                self.assertRaises(images.ReleaseError),
            ):
                rollout.smoke("https://closet.example.test")

    def test_native_cli_rejects_a_bad_update_and_confirms_rollback_with_local_aws_stub(
        self,
    ):
        self.tools.fail_forward = "web"
        with self.assertRaises(images.ReleaseError):
            self.run_rollout()
        root = Path(self.directory.name)
        for key, data in [
            ("manifest", self.manifest),
            ("infrastructure", self.outputs),
            ("migration", self.receipt),
            ("trace", self.tools.trace),
        ]:
            (root / (key + ".json")).write_text(json.dumps(data))
        stub = root / "aws"
        stub.write_text(
            "#!/usr/bin/env python3\nimport json, os, pathlib, sys\np = pathlib.Path(os.environ['RELEASE_TOOL_TRACE'])\nrows = json.loads(p.read_text())\nrow = rows.pop(0)\nif sys.argv[1:] != row['arguments']: sys.exit(9)\np.write_text(json.dumps(rows))\nprint(json.dumps(row['response']))\n"
        )
        stub.chmod(0o700)
        args = [
            sys.executable,
            str(Path(rollout.__file__).resolve()),
            "deploy",
            "--account",
            self.release["accountId"],
            "--region",
            self.release["region"],
            "--source",
            self.release["sourceCommit"],
            "--publication",
            self.release["publicationId"],
            "--manifest",
            str(root / "manifest.json"),
            "--infrastructure",
            str(root / "infrastructure.json"),
            "--migration",
            str(root / "migration.json"),
            "--destination",
            str(root / "native.json"),
            "--timeout",
            "30",
        ]
        environment = {
            key: value
            for key, value in os.environ.items()
            if not key.startswith("AWS_")
        }
        result = subprocess.run(
            args,
            check=False,
            env={
                **environment,
                "PATH": str(root) + os.pathsep + environment["PATH"],
                "RELEASE_TOOL_TRACE": str(root / "trace.json"),
            },
            text=True,
            capture_output=True,
            timeout=30,
        )
        self.assertEqual(result.returncode, 1, result.stderr)
        self.assertIn("restored", result.stderr)
        self.assertEqual(
            json.loads((root / "native.json").read_text())["status"], "ROLLED_BACK"
        )
        self.assertEqual(json.loads((root / "trace.json").read_text()), [])


class SmokeTransportTest(unittest.TestCase):
    def test_redirects_are_not_followed_and_error_bodies_are_closed_and_bounded(self):
        paths = []

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_args):
                pass

            def do_GET(self):
                paths.append(self.path)
                self.send_response(302 if self.path == "/redirect" else 401)
                self.send_header("Content-Type", "application/json")
                self.send_header("Location", "/secret")
                self.end_headers()
                self.wfile.write(b"x" * (131073 if self.path == "/oversized" else 2))

        server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            origin = f"http://127.0.0.1:{server.server_port}"
            self.assertEqual(rollout.http_get(origin + "/redirect")[0], 302)
            self.assertEqual(paths, ["/redirect"])
            self.assertEqual(rollout.http_get(origin + "/denied")[0], 401)
            with self.assertRaisesRegex(images.ReleaseError, "size"):
                rollout.http_get(origin + "/oversized")
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=5)


if __name__ == "__main__":
    unittest.main()
