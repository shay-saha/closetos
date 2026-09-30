import copy
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import release_deployment as deployment
import release_images as images


class ReleaseDeploymentTest(unittest.TestCase):
    def setUp(self):
        self.release = images.metadata(
            "123456789012", "eu-west-2", "closetos", "dev", "c" * 40, "9876-2"
        )
        self.roles = {
            key: "arn:aws:iam::123456789012:role/closetos-dev-github-" + suffix
            for key, suffix in deployment.ROLES.items()
        }
        self.environment = {
            "name": "production",
            "protection_rules": [
                {
                    "type": "required_reviewers",
                    "reviewers": [{"type": "User", "reviewer": {"id": 12345}}],
                }
            ],
            "deployment_branch_policy": {
                "protected_branches": False,
                "custom_branch_policies": True,
            },
        }
        self.branches = {
            "total_count": 1,
            "branch_policies": [{"name": "main", "type": "branch"}],
        }
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)

    def protection(self, environment=None, branches=None):
        with patch.object(
            deployment,
            "github",
            side_effect=[
                environment if environment is not None else self.environment,
                branches if branches is not None else self.branches,
            ],
        ):
            deployment.production_protection("wardrobe-owner/closetos")

    def test_each_deployment_phase_requires_its_own_exact_environment_role(self):
        deployment.check_roles(self.release, self.roles)
        for key in self.roles:
            altered = {
                **self.roles,
                key: self.roles[key].replace("closetos-dev", "closetos-prod"),
            }
            with self.subTest(key=key), self.assertRaises(images.ReleaseError):
                deployment.check_roles(self.release, altered)
        with self.assertRaises(images.ReleaseError):
            deployment.check_roles(
                self.release,
                {
                    key: value
                    for key, value in self.roles.items()
                    if key != "initializer"
                },
            )

    def test_production_cannot_fall_back_to_development_mutation_roles(self):
        with self.assertRaises(images.ReleaseError):
            deployment.check_roles({**self.release, "environment": "prod"}, self.roles)

    def test_production_requires_reviewers_and_the_exact_main_branch(self):
        self.protection()
        for mutate in [
            lambda value: value.update(name="staging"),
            lambda value: value.update(protection_rules=[]),
            lambda value: value.update(
                protection_rules=[{"type": "wait_timer", "wait_timer": 30}]
            ),
            lambda value: value.update(
                protection_rules=[{"type": "required_reviewers", "reviewers": []}]
            ),
            lambda value: value.update(deployment_branch_policy=None),
            lambda value: value.update(
                deployment_branch_policy={
                    "protected_branches": True,
                    "custom_branch_policies": False,
                }
            ),
        ]:
            environment = copy.deepcopy(self.environment)
            mutate(environment)
            with self.assertRaises(images.ReleaseError):
                self.protection(environment=environment)
        for branches in [
            {"total_count": 0, "branch_policies": []},
            {"total_count": 1, "branch_policies": [{"name": "*", "type": "branch"}]},
            {"total_count": 1, "branch_policies": [{"name": "main", "type": "tag"}]},
            {
                "total_count": 2,
                "branch_policies": [
                    {"name": "main", "type": "branch"},
                    {"name": "release-*", "type": "branch"},
                ],
            },
        ]:
            with self.assertRaises(images.ReleaseError):
                self.protection(branches=branches)

    def test_protection_inspection_is_read_only_and_uses_the_versioned_github_api(self):
        responses = [json.dumps(self.environment), json.dumps(self.branches)]
        with patch.object(deployment, "run", side_effect=responses) as commands:
            deployment.production_protection("wardrobe-owner/closetos")
        requests = [call.args[0] for call in commands.call_args_list]
        self.assertTrue(
            all(
                command[:2] == ["gh", "api"] and "--method" not in command
                for command in requests
            )
        )
        self.assertEqual(
            requests[0][2], "repos/wardrobe-owner/closetos/environments/production"
        )
        self.assertEqual(
            requests[1][2],
            "repos/wardrobe-owner/closetos/environments/production/deployment-branch-policies?per_page=100",
        )
        self.assertTrue(
            all("X-GitHub-Api-Version: 2026-03-10" in command for command in requests)
        )

    def test_unavailable_or_invalid_protection_metadata_never_allows_production(self):
        for response in ["not json", "null", "[]"]:
            with (
                patch.object(deployment, "run", return_value=response),
                self.assertRaises(images.ReleaseError),
            ):
                deployment.production_protection("wardrobe-owner/closetos")
        with (
            patch.object(
                deployment,
                "run",
                side_effect=images.ReleaseError("GitHub metadata unavailable"),
            ),
            self.assertRaises(images.ReleaseError),
        ):
            deployment.production_protection("wardrobe-owner/closetos")

    def test_repositories_cannot_inject_flags_or_paths_into_protection_inspection(self):
        for repository in [
            None,
            "--method=PUT",
            "wardrobe-owner/closetos/../../other",
            "https://github.com/owner/repo",
        ]:
            with (
                patch.object(deployment, "run") as commands,
                self.assertRaises(images.ReleaseError),
            ):
                deployment.production_protection(repository)
            commands.assert_not_called()

    def receipts(self, status="SUCCEEDED"):
        for name in [
            "secrets.json",
            "migration.json",
            "rollout.json",
            "scaling/result.json",
        ]:
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(json.dumps({**self.release, "status": status}))

    def test_success_requires_a_bound_success_receipt_from_every_deployment_phase(self):
        self.receipts()
        result = deployment.report(
            self.release, self.root, self.root / "deployment.json"
        )
        self.assertEqual(result["status"], "SUCCEEDED")
        self.assertEqual(
            result["stages"],
            {
                key: "SUCCEEDED"
                for key in ["secrets", "migration", "rollout", "scaling"]
            },
        )
        self.assertEqual(
            json.loads((self.root / "deployment.json").read_text()), result
        )

    def test_missing_phases_and_rollback_results_are_reported_as_failed_deployments(
        self,
    ):
        result = deployment.report(self.release, self.root, self.root / "missing.json")
        self.assertEqual(result["status"], "FAILED")
        self.assertEqual(set(result["stages"].values()), {"UNCONFIRMED"})
        self.receipts()
        (self.root / "rollout.json").write_text(
            json.dumps({**self.release, "status": "ROLLBACK_UNCONFIRMED"})
        )
        result = deployment.report(self.release, self.root, self.root / "failed.json")
        self.assertEqual(result["status"], "FAILED")
        self.assertEqual(result["stages"]["rollout"], "ROLLBACK_UNCONFIRMED")

    def test_stale_or_foreign_receipts_cannot_make_the_current_workflow_green(self):
        self.receipts()
        for field, value in [
            ("sourceCommit", "f" * 40),
            ("publicationId", "9999-1"),
            ("environment", "prod"),
            ("status", "COMPLETED"),
        ]:
            (self.root / "migration.json").write_text(
                json.dumps(
                    {
                        **self.release,
                        field: value,
                        **({} if field == "status" else {"status": "SUCCEEDED"}),
                    }
                )
            )
            with self.subTest(field=field), self.assertRaises(images.ReleaseError):
                deployment.report(
                    self.release, self.root, self.root / "deployment.json"
                )
            self.assertFalse((self.root / "deployment.json").exists())


if __name__ == "__main__":
    unittest.main()
