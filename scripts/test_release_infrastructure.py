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
import release_infrastructure as infrastructure
from test_release_rollout import fixture as rollout_fixture


def fixture():
    release, manifest, outputs, _ = rollout_fixture()
    supplied = {
        "app_domain": "closet.example.test",
        "cloudfront_public_key": (
            infrastructure.MODULE / "tests/media-signing.pub"
        ).read_text(),
        "embedding_model_arn": "arn:aws:bedrock:us-east-1::foundation-model/amazon.titan-embed-image-v1",
        "analysis_model_id": "arn:aws:bedrock:us-east-1::foundation-model/anthropic.claude-sonnet-4-5-20250929-v1:0",
        "analysis_model_arns": [
            "arn:aws:bedrock:us-east-1::foundation-model/anthropic.claude-sonnet-4-5-20250929-v1:0"
        ],
        "route53_zone_id": "ZEXAMPLE123",
        "budget_alert_emails": ["operator@example.test"],
    }
    return release, manifest, outputs, supplied


def change(kind, before=None, after=None, actions=None):
    return {
        "address": kind + ".application",
        "type": kind,
        "mode": "managed",
        "change": {"actions": actions or ["update"], "before": before, "after": after},
    }


def plan(changes=None):
    return {
        "format_version": "1.2",
        "terraform_version": "1.16.4",
        "complete": True,
        "errored": False,
        "resource_changes": changes or [],
    }


def application_role(release, suffix="api-task"):
    prefix = f"{release['application']}-{release['environment']}"
    name = f"{prefix}-{suffix}"
    workflow = suffix == "media-workflow"
    return {
        "name": name,
        "permissions_boundary": f"arn:aws:iam::{release['accountId']}:policy/{name}-permissions-boundary",
        "assume_role_policy": json.dumps(
            {
                "Version": "2012-10-17",
                "Statement": [
                    {
                        "Effect": "Allow",
                        "Principal": {
                            "Service": "states.amazonaws.com"
                            if workflow
                            else "ecs-tasks.amazonaws.com"
                        },
                        "Action": "sts:AssumeRole",
                        "Condition": {
                            "StringEquals": {"aws:SourceAccount": release["accountId"]},
                            "ArnEquals" if workflow else "ArnLike": {
                                "aws:SourceArn": (
                                    f"arn:aws:states:{release['region']}:{release['accountId']}:stateMachine:{prefix}-media"
                                    if workflow
                                    else f"arn:aws:ecs:{release['region']}:{release['accountId']}:*"
                                )
                            },
                        },
                    }
                ],
            }
        ),
    }


class InfrastructureTools:
    def __init__(self, release, manifest, outputs, supplied):
        self.release, self.outputs = release, outputs
        self.variables = infrastructure.configuration(release, manifest, supplied)
        self.calls = []
        self.account = release["accountId"]
        self.source = release["sourceCommit"]
        self.dirty, self.untracked = False, False
        self.scaling = False
        self.plan = plan(
            [
                change(
                    "aws_ecs_service",
                    {"desired_count": 3, "task_definition": "prior:7"},
                    {"desired_count": 3, "task_definition": "prior:7"},
                )
            ]
        )
        self.failed_apply = False
        self.activated = False
        self.mismatch_variables = False
        self.healthy = True

    def api(self, region, service, action, *arguments):
        self.calls.append((service, action, list(arguments)))
        if action == "get-caller-identity":
            return {"Account": self.account}
        if action == "describe-services":
            target = infrastructure.rollout_context(self.outputs, self.release)
            return {
                "services": [
                    {
                        "serviceName": name,
                        "clusterArn": target["cluster"],
                        "status": "ACTIVE",
                        "desiredCount": 1,
                        "runningCount": 1 if self.healthy else 0,
                        "pendingCount": 0,
                        "taskDefinition": target["definitions"][key],
                        "deployments": [
                            {
                                "status": "PRIMARY",
                                "rolloutState": "COMPLETED",
                                "taskDefinition": target["definitions"][key],
                            }
                        ],
                    }
                    for key, name in target["services"].items()
                ],
                "failures": [],
            }
        if action == "describe-state-machine":
            target = infrastructure.rollout_context(self.outputs, self.release)
            return {
                "stateMachineArn": target["machine"],
                "status": "ACTIVE",
                "definition": json.dumps(target["workflow"]),
            }
        raise AssertionError(f"Unexpected infrastructure observation: {action}")

    def state(self):
        return {
            "values": {
                "root_module": {
                    "resources": [
                        {"type": "aws_appautoscaling_target", "mode": "managed"}
                        for _ in range(2 if self.scaling or self.activated else 0)
                    ]
                }
            }
        }

    def terraform(self, action, *arguments):
        self.calls.append(("terraform", action, list(arguments)))
        if action in {"init", "fmt", "validate"}:
            return ""
        if action == "show":
            if len(arguments) == 1:
                return json.dumps(self.state())
            rendered = copy.deepcopy(self.plan)
            rendered["variables"] = {
                key: {"value": value} for key, value in self.variables.items()
            }
            if self.mismatch_variables:
                rendered["variables"]["environment"]["value"] = "prod"
            return json.dumps(rendered)
        if action == "plan":
            variables = next(
                value.split("=", 1)[1]
                for value in arguments
                if value.startswith("-var-file=")
            )
            self.variables = json.loads(Path(variables).read_text())
            destination = next(
                value.split("=", 1)[1]
                for value in arguments
                if value.startswith("-out=")
            )
            Path(destination).write_bytes(b"terraform plan binary bound to release")
            return "Plan created"
        if action == "apply":
            if self.failed_apply:
                raise images.ReleaseError("Stale or failed Terraform apply")
            if str(arguments[-1]).endswith("scaling.tfplan"):
                self.activated = True
            return "Apply complete"
        if action == "output":
            return json.dumps(self.outputs)
        raise AssertionError(f"Unexpected Terraform action: {action}")

    def run(self, command, *, stdin=None):
        if command[0] == "git":
            self.calls.append(("git", command[3], command[4:]))
            if command[3] == "rev-parse":
                return self.source
            if command[3] == "diff" and self.dirty:
                raise images.ReleaseError("Tracked files changed")
            if command[3] == "ls-files":
                return "infra/aws/untracked.tf" if self.untracked else ""
            return ""
        return images.run(command, stdin=stdin)


class ReleaseInfrastructureTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.bundle, self.output = (
            self.root / "bundle",
            self.root / "infrastructure.json",
        )
        self.release, self.manifest, self.outputs, self.supplied = fixture()
        self.tools = InfrastructureTools(
            self.release, self.manifest, self.outputs, self.supplied
        )
        self.patches = [
            patch.object(infrastructure, "terraform", self.tools.terraform),
            patch.object(infrastructure, "api", self.tools.api),
            patch.object(infrastructure, "run", self.tools.run),
        ]
        for active in self.patches:
            active.start()
            self.addCleanup(active.stop)

    def prepare(self):
        return infrastructure.plan_release(
            self.release, self.manifest, self.supplied, self.bundle
        )

    def apply(self):
        return infrastructure.apply_release(
            self.release, self.manifest, self.bundle, self.output
        )

    def receipt(self):
        target = infrastructure.rollout_context(self.outputs, self.release)
        return {
            **self.release,
            "status": "SUCCEEDED",
            "taskDefinitions": target["definitions"],
            "clusterArn": target["cluster"],
            "stateMachineArn": target["machine"],
        }

    def actions(self):
        return [action for tool, action, _ in self.tools.calls if tool == "terraform"]

    def test_prepare_binds_exact_images_metadata_and_the_saved_plan(self):
        result = self.prepare()
        self.assertEqual(result["status"], "PLANNED")
        self.assertEqual(result["sourceCommit"], self.release["sourceCommit"])
        self.assertEqual(
            result["planDigest"], infrastructure.digest(self.bundle / "prepare.tfplan")
        )
        self.assertEqual(
            result["variablesDigest"],
            infrastructure.digest(self.bundle / "release.tfvars.json"),
        )
        self.assertEqual(
            self.tools.variables["image_digests"],
            {
                key: value["imageDigest"]
                for key, value in self.manifest["images"].items()
            },
        )
        self.assertFalse(self.tools.variables["services_enabled"])
        self.assertTrue(
            self.tools.variables["application_permissions_boundaries_enabled"]
        )
        self.assertNotIn("apply", self.actions())
        init = next(
            args
            for tool, action, args in self.tools.calls
            if tool == "terraform" and action == "init"
        )
        for value in [
            "-backend-config=bucket=closetos-dev-123456789012-state",
            "-backend-config=key=dev/closetos.tfstate",
            "-backend-config=encrypt=true",
            "-backend-config=use_lockfile=true",
            "-lockfile=readonly",
        ]:
            self.assertIn(value, init)
        self.assertEqual(self.bundle.stat().st_mode & 0o777, 0o700)

    def test_preparing_an_existing_release_preserves_its_active_autoscaling_targets(
        self,
    ):
        self.tools.scaling = True
        self.prepare()
        self.assertTrue(self.tools.variables["services_enabled"])

    def test_apply_uses_the_bound_binary_plan_and_exports_only_scoped_outputs(self):
        self.prepare()
        result = self.apply()
        self.assertEqual(result, self.outputs)
        self.assertEqual(json.loads(self.output.read_text()), self.outputs)
        apply = next(
            args
            for tool, action, args in self.tools.calls
            if tool == "terraform" and action == "apply"
        )
        self.assertEqual(apply[-1], str(self.bundle / "prepare.tfplan"))
        self.assertFalse(any(value.startswith("-var") for value in apply))
        self.assertEqual(self.output.stat().st_mode & 0o777, 0o600)

    def test_wrong_account_or_source_and_modified_release_code_cannot_plan_or_apply(
        self,
    ):
        for field, value in [
            ("account", "999999999999"),
            ("source", "f" * 40),
            ("dirty", True),
            ("untracked", True),
        ]:
            with self.subTest(field=field):
                setattr(self.tools, field, value)
                with self.assertRaises(images.ReleaseError):
                    self.prepare()
                self.assertNotIn("plan", self.actions())
                setattr(
                    self.tools,
                    field,
                    self.release["accountId"]
                    if field == "account"
                    else self.release["sourceCommit"]
                    if field == "source"
                    else False,
                )

    def test_configuration_cannot_override_release_controls_or_include_credential_values(
        self,
    ):
        for key, value in [
            ("image_digests", {}),
            ("services_enabled", True),
            ("application_permissions_boundaries_enabled", False),
            ("environment", "prod"),
            ("database_password", "do-not-save"),
            ("cloudfront_public_key", "-----BEGIN PRIVATE KEY-----"),
        ]:
            supplied = {**self.supplied, key: value}
            with self.subTest(key=key), self.assertRaises(images.ReleaseError):
                infrastructure.configuration(self.release, self.manifest, supplied)

    def test_application_iam_changes_require_each_roles_own_bootstrap_boundary(self):
        for suffix in infrastructure.APPLICATION_ROLES:
            after = application_role(self.release, suffix)
            before = {**after, "permissions_boundary": None}
            changes = plan([change("aws_iam_role", before, after)])
            self.assertEqual(
                len(infrastructure.inspect_plan(changes, "prepare", self.release)), 1
            )
            for boundary in [
                None,
                after["permissions_boundary"].replace("123456789012", "999999999999"),
                after["permissions_boundary"].replace("closetos-dev", "closetos-prod"),
                "arn:aws:iam::123456789012:policy/AdministratorAccess",
                "arn:aws:iam::123456789012:policy/closetos-dev-unrelated-permissions-boundary",
            ]:
                with (
                    self.subTest(role=suffix, boundary=boundary),
                    self.assertRaises(images.ReleaseError),
                ):
                    infrastructure.inspect_plan(
                        plan(
                            [
                                change(
                                    "aws_iam_role",
                                    after,
                                    {**after, "permissions_boundary": boundary},
                                )
                            ]
                        ),
                        "prepare",
                        self.release,
                    )

    def test_release_iam_scope_excludes_bootstrap_roles_policies_and_federation(self):
        values = application_role(self.release)
        with self.assertRaises(images.ReleaseError):
            infrastructure.inspect_plan(
                plan(
                    [
                        change(
                            "aws_iam_role",
                            after={
                                **values,
                                "name": "closetos-dev-github-infrastructure",
                            },
                        )
                    ]
                ),
                "prepare",
                self.release,
            )
        for kind in [
            "aws_iam_policy",
            "aws_iam_openid_connect_provider",
            "aws_iam_role_policy_attachment",
            "aws_iam_user",
        ]:
            with self.subTest(kind=kind), self.assertRaises(images.ReleaseError):
                infrastructure.inspect_plan(
                    plan(
                        [change(kind, after={"name": "bootstrap"}, actions=["create"])]
                    ),
                    "prepare",
                    self.release,
                )
        with self.assertRaises(images.ReleaseError):
            infrastructure.inspect_plan(
                plan([change("aws_iam_role", after=values)]), "prepare"
            )
        for actions in [["delete"], ["delete", "create"], ["create", "delete"]]:
            with self.subTest(actions=actions), self.assertRaises(images.ReleaseError):
                infrastructure.inspect_plan(
                    plan([change("aws_iam_role", values, values, actions)]),
                    "prepare",
                    self.release,
                )

    def test_release_iam_trust_cannot_be_broadened_to_other_principals_or_workloads(
        self,
    ):
        for suffix in ["api-task", "media-workflow"]:
            values = application_role(self.release, suffix)
            original = json.loads(values["assume_role_policy"])
            for kind in ["principal", "account", "source", "action", "extra-statement"]:
                trust = copy.deepcopy(original)
                statement = trust["Statement"][0]
                if kind == "principal":
                    statement["Principal"] = {"AWS": "arn:aws:iam::123456789012:root"}
                elif kind == "account":
                    statement["Condition"]["StringEquals"]["aws:SourceAccount"] = (
                        "999999999999"
                    )
                elif kind == "source":
                    key = "ArnEquals" if suffix == "media-workflow" else "ArnLike"
                    statement["Condition"][key]["aws:SourceArn"] = "*"
                elif kind == "action":
                    statement["Action"] = [
                        "sts:AssumeRole",
                        "sts:AssumeRoleWithWebIdentity",
                    ]
                else:
                    trust["Statement"].append(
                        {
                            "Effect": "Allow",
                            "Principal": "*",
                            "Action": "sts:AssumeRole",
                        }
                    )
                with (
                    self.subTest(role=suffix, mutation=kind),
                    self.assertRaises(images.ReleaseError),
                ):
                    infrastructure.inspect_plan(
                        plan(
                            [
                                change(
                                    "aws_iam_role",
                                    values,
                                    {**values, "assume_role_policy": json.dumps(trust)},
                                )
                            ]
                        ),
                        "prepare",
                        self.release,
                    )

    def test_unbounded_role_is_rejected_before_a_review_receipt_is_published(self):
        values = {**application_role(self.release), "permissions_boundary": None}
        self.tools.plan = plan(
            [change("aws_iam_role", after=values, actions=["create"])]
        )
        with self.assertRaises(images.ReleaseError):
            self.prepare()
        self.assertFalse((self.bundle / "plan.json").exists())
        self.assertNotIn("apply", self.actions())

    def test_boundaries_are_rechecked_before_applying_a_saved_release_plan(self):
        self.prepare()
        values = {**application_role(self.release), "permissions_boundary": None}
        self.tools.plan = plan(
            [change("aws_iam_role", after=values, actions=["create"])]
        )
        with self.assertRaises(images.ReleaseError):
            self.apply()
        self.assertNotIn("apply", self.actions())

    def test_modified_plan_variables_or_receipt_are_rejected_before_aws_access(self):
        self.prepare()
        originals = {
            key: (self.bundle / key).read_bytes()
            for key in [
                "prepare.tfplan",
                "release.tfvars.json",
                "review.json",
                "plan.json",
            ]
        }
        for key, value in originals.items():
            with self.subTest(key=key):
                (self.bundle / key).write_bytes(b"changed artifact")
                self.tools.calls.clear()
                with self.assertRaises(images.ReleaseError):
                    self.apply()
                self.assertEqual(self.tools.calls, [])
                (self.bundle / key).write_bytes(value)

    def test_a_different_publication_cannot_apply_a_previously_reviewed_plan(self):
        self.prepare()
        release = {**self.release, "publicationId": "1111-1"}
        self.tools.calls.clear()
        with self.assertRaises(images.ReleaseError):
            infrastructure.apply_release(
                release, self.manifest, self.bundle, self.output
            )
        self.assertEqual(self.tools.calls, [])

    def test_plan_failures_and_stale_apply_never_publish_infrastructure_results(self):
        self.prepare()
        self.tools.failed_apply = True
        with self.assertRaises(images.ReleaseError):
            self.apply()
        self.assertFalse(self.output.exists())

    def test_binary_plan_variables_are_rechecked_before_apply(self):
        self.prepare()
        self.tools.mismatch_variables = True
        with self.assertRaisesRegex(images.ReleaseError, "binary Terraform plan"):
            self.apply()
        self.assertNotIn("apply", self.actions())

    def test_unsafe_plans_cannot_be_saved_as_reviewable_release_artifacts(self):
        for resource in [
            change("aws_ecs_service", None, {"desired_count": 1}, ["create"]),
            change(
                "aws_ecs_service",
                {"desired_count": 3, "task_definition": "prior:7"},
                {"desired_count": 1, "task_definition": "prior:7"},
            ),
            change(
                "aws_ecs_service",
                {"desired_count": 3, "task_definition": "prior:7"},
                {"desired_count": 3, "task_definition": "new:8"},
            ),
            change(
                "aws_sfn_state_machine", {"definition": "old"}, {"definition": "new"}
            ),
            change(
                "aws_db_instance",
                {"id": "data"},
                {"id": "replacement"},
                ["delete", "create"],
            ),
            change(
                "aws_cloudfront_public_key",
                {"id": "old"},
                {"id": "new"},
                ["delete", "create"],
            ),
            change(
                "aws_secretsmanager_secret_version",
                None,
                {"secret_string": "credential"},
                ["create"],
            ),
            change(
                "aws_ecs_task_definition",
                {"skip_destroy": False},
                {"skip_destroy": True},
                ["delete", "create"],
            ),
            change(
                "aws_ecs_task_definition",
                None,
                {
                    "family": "closetos-dev-api",
                    "skip_destroy": True,
                    "container_definitions": json.dumps(
                        [
                            {
                                "environment": [
                                    {
                                        "name": "DATABASE_PASSWORD",
                                        "value": "do-not-save",
                                    }
                                ]
                            }
                        ]
                    ),
                },
                ["create"],
            ),
        ]:
            with (
                self.subTest(
                    type=resource["type"], actions=resource["change"]["actions"]
                ),
                self.assertRaises(images.ReleaseError),
            ):
                infrastructure.inspect_plan(plan([resource]), "prepare")

    def test_safe_replacements_keep_previous_task_definitions_registered(self):
        change_set = [
            change(
                "aws_ecs_task_definition",
                {"skip_destroy": True},
                {"skip_destroy": True},
                ["delete", "create"],
            )
        ]
        self.assertEqual(
            len(infrastructure.inspect_plan(plan(change_set), "prepare")), 1
        )
        migration = {"family": "closetos-dev-migration", "skip_destroy": True}
        self.assertEqual(
            len(
                infrastructure.inspect_plan(
                    plan(
                        [
                            change(
                                "aws_ecs_task_definition",
                                migration,
                                migration,
                                ["delete", "create"],
                            )
                        ]
                    ),
                    "prepare",
                )
            ),
            1,
        )

    def test_foundation_creation_and_signing_changes_require_administration(self):
        for kind in infrastructure.FOUNDATION_ADMIN_RESOURCES:
            with (
                self.subTest(kind=kind),
                self.assertRaisesRegex(
                    images.ReleaseError, "Initialize the environment foundation"
                ),
            ):
                infrastructure.inspect_plan(
                    plan([change(kind, None, {}, ["create"])]), "prepare"
                )
        for kind in [
            "aws_cloudfront_public_key",
            "aws_cloudfront_key_group",
            "aws_cloudfront_origin_access_control",
        ]:
            with (
                self.subTest(kind=kind),
                self.assertRaisesRegex(images.ReleaseError, "account administration"),
            ):
                infrastructure.inspect_plan(
                    plan([change(kind, {"name": "old"}, {"name": "new"})]), "prepare"
                )
            self.assertEqual(
                infrastructure.inspect_plan(
                    plan([change(kind, {"name": "old"}, {"name": "old"}, ["no-op"])]),
                    "prepare",
                ),
                [],
            )

    def test_infrastructure_cannot_change_credential_container_configuration(self):
        before = {
            "name": "closetos-dev/database-app",
            "description": "App database",
            "kms_key_id": "key-1",
            "tags": {"Environment": "dev"},
        }
        for key, value in [
            ("name", "foreign"),
            ("description", "changed"),
            ("kms_key_id", "key-2"),
        ]:
            with (
                self.subTest(key=key),
                self.assertRaisesRegex(images.ReleaseError, "credential containers"),
            ):
                infrastructure.inspect_plan(
                    plan(
                        [
                            change(
                                "aws_secretsmanager_secret",
                                before,
                                {**before, key: value},
                            )
                        ]
                    ),
                    "prepare",
                )
        after = {
            **before,
            "tags": {"Environment": "dev", "Name": "database"},
            "recovery_window_in_days": 30,
        }
        self.assertEqual(
            len(
                infrastructure.inspect_plan(
                    plan([change("aws_secretsmanager_secret", before, after)]),
                    "prepare",
                )
            ),
            1,
        )

    def test_migration_revisions_must_also_remain_registered(self):
        for actions, before, after in [
            (
                ["delete", "create"],
                {"family": "closetos-dev-migration", "skip_destroy": False},
                {"skip_destroy": True},
            ),
            (
                ["create"],
                None,
                {"family": "closetos-dev-migration", "skip_destroy": False},
            ),
        ]:
            with (
                self.subTest(actions=actions),
                self.assertRaisesRegex(images.ReleaseError, "task definitions"),
            ):
                infrastructure.inspect_plan(
                    plan([change("aws_ecs_task_definition", before, after, actions)]),
                    "prepare",
                )

    def test_release_plans_cannot_create_database_or_browser_credentials(self):
        for resource in [
            change(
                "aws_cognito_user_pool_client",
                None,
                {"generate_secret": True},
                ["create"],
            ),
            change(
                "aws_db_instance",
                {"manage_master_user_password": True},
                {"manage_master_user_password": False},
            ),
            change("aws_db_instance", {}, {"password": "credential"}),
            change("aws_db_instance", {}, {"password_wo": "credential"}),
        ]:
            with (
                self.subTest(kind=resource["type"]),
                self.assertRaisesRegex(images.ReleaseError, "credentials"),
            ):
                infrastructure.inspect_plan(plan([resource]), "prepare")
        resource = change("aws_db_instance", {}, {})
        resource["change"]["after_unknown"] = {"password": True}
        with self.assertRaisesRegex(images.ReleaseError, "credentials"):
            infrastructure.inspect_plan(plan([resource]), "prepare")
        infrastructure.inspect_plan(
            plan(
                [
                    change(
                        "aws_db_instance",
                        {},
                        {
                            "manage_master_user_password": True,
                            "password": None,
                            "password_wo": None,
                        },
                    )
                ]
            ),
            "prepare",
        )
        infrastructure.inspect_plan(
            plan(
                [change("aws_cognito_user_pool_client", {}, {"generate_secret": False})]
            ),
            "prepare",
        )

    def test_private_service_discovery_namespace_cannot_be_replaced(self):
        with self.assertRaisesRegex(images.ReleaseError, "protected application"):
            infrastructure.inspect_plan(
                plan(
                    [
                        change(
                            "aws_service_discovery_private_dns_namespace",
                            {"id": "ns-old"},
                            {"id": "ns-new"},
                            ["delete", "create"],
                        )
                    ]
                ),
                "prepare",
            )

    def test_scaling_waits_for_a_bound_successful_rollout_and_rejects_unrelated_drift(
        self,
    ):
        self.prepare()
        for key, value in [
            ("status", "ROLLED_BACK"),
            ("sourceCommit", "f" * 40),
            ("clusterArn", "foreign"),
            ("taskDefinitions", {}),
        ]:
            receipt = {**self.receipt(), key: value}
            with self.subTest(key=key), self.assertRaises(images.ReleaseError):
                infrastructure.activate_scaling(
                    self.release,
                    self.manifest,
                    self.bundle,
                    receipt,
                    self.root / "scaling",
                )
            self.assertFalse(self.tools.activated)
        self.tools.plan = plan(
            [
                change(
                    "aws_db_instance",
                    {"instance_class": "db.t4g.micro"},
                    {"instance_class": "db.t4g.small"},
                )
            ]
        )
        with self.assertRaisesRegex(images.ReleaseError, "unrelated infrastructure"):
            infrastructure.activate_scaling(
                self.release,
                self.manifest,
                self.bundle,
                self.receipt(),
                self.root / "scaling",
            )
        self.assertFalse(self.tools.activated)

    def test_scaling_activates_both_targets_only_after_the_successful_release(self):
        self.prepare()
        self.tools.plan = plan(
            [
                change(
                    "aws_appautoscaling_target",
                    None,
                    {"min_capacity": 1, "max_capacity": 4},
                    ["create"],
                ),
                change(
                    "aws_appautoscaling_policy", None, {"target_value": 65}, ["create"]
                ),
            ]
        )
        result = infrastructure.activate_scaling(
            self.release,
            self.manifest,
            self.bundle,
            self.receipt(),
            self.root / "scaling",
        )
        self.assertTrue(self.tools.activated)
        self.assertTrue(self.tools.variables["services_enabled"])
        self.assertEqual(result["status"], "SUCCEEDED")

    def test_sensitive_or_foreign_outputs_are_never_published(self):
        self.prepare()
        self.outputs["database_secret_arn"]["sensitive"] = True
        with self.assertRaisesRegex(images.ReleaseError, "Credential values"):
            self.apply()
        self.assertFalse(self.output.exists())

    def test_scaling_activation_rechecks_the_current_services_instead_of_trusting_an_old_success(
        self,
    ):
        self.prepare()
        self.tools.healthy = False
        with self.assertRaisesRegex(images.ReleaseError, "remain healthy"):
            infrastructure.activate_scaling(
                self.release,
                self.manifest,
                self.bundle,
                self.receipt(),
                self.root / "scaling",
            )
        self.assertFalse(self.tools.activated)

    def test_review_artifacts_show_changes_while_redacting_provider_sensitive_values(
        self,
    ):
        resource = change(
            "aws_db_instance",
            {"instance_class": "db.t4g.micro", "password": "must-not-appear"},
            {
                "instance_class": "db.t4g.small",
                "connection": {"credential": "also-hidden"},
                "private_key": "private-value",
            },
        )
        resource["change"]["before_sensitive"] = {"password": True}
        resource["change"]["after_sensitive"] = {"connection": {"credential": True}}
        self.tools.plan = plan([resource])
        receipt = self.prepare()
        review = self.bundle / "review.json"
        self.assertEqual(receipt["reviewDigest"], infrastructure.digest(review))
        contents = review.read_text()
        self.assertNotIn("must-not-appear", contents)
        self.assertNotIn("also-hidden", contents)
        self.assertNotIn("private-value", contents)
        self.assertIn("db.t4g.small", contents)
        self.assertIn("[sensitive]", contents)

    def test_existing_plan_and_result_destinations_are_rejected(self):
        self.prepare()
        self.tools.calls.clear()
        with self.assertRaisesRegex(images.ReleaseError, "fresh"):
            self.prepare()
        self.assertEqual(self.tools.calls, [])
        self.output.write_text("existing")
        with self.assertRaisesRegex(images.ReleaseError, "fresh"):
            self.apply()
        self.assertEqual(self.tools.calls, [])

    def test_incomplete_unknown_format_and_partial_scaling_state_fail_closed(self):
        for key, value in [
            ("complete", False),
            ("errored", True),
            ("format_version", "99.0"),
            ("terraform_version", "1.17.0"),
        ]:
            with self.subTest(key=key), self.assertRaises(images.ReleaseError):
                infrastructure.inspect_plan({**plan(), key: value}, "prepare")
        with self.assertRaisesRegex(images.ReleaseError, "both service autoscaling"):
            infrastructure.scaling_enabled(
                {
                    "values": {
                        "root_module": {
                            "resources": [
                                {"type": "aws_appautoscaling_target", "mode": "managed"}
                            ]
                        }
                    }
                }
            )

    def test_native_cli_checks_public_configuration_without_aws_or_terraform(self):
        for key, value in [("manifest", self.manifest), ("variables", self.supplied)]:
            (self.root / (key + ".json")).write_text(json.dumps(value))
        arguments = [
            sys.executable,
            str(Path(infrastructure.__file__).resolve()),
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
            str(self.root / "manifest.json"),
            "--variables",
            str(self.root / "variables.json"),
        ]
        environment = {
            key: value
            for key, value in os.environ.items()
            if not key.startswith("AWS_")
        }
        result = subprocess.run(
            arguments,
            env=environment,
            check=False,
            capture_output=True,
            text=True,
            timeout=30,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("verified", result.stdout)


if __name__ == "__main__":
    unittest.main()
