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
import release_secrets as initialization


def fixture(public):
    release = images.metadata(
        "123456789012", "eu-west-2", "closetos", "dev", "c" * 40, "9876-2"
    )
    outputs = {
        "application_secret_arns": {
            "value": {
                key: f"arn:aws:secretsmanager:eu-west-2:123456789012:secret:closetos-dev/{key}-AbCd12"
                for key in initialization.SECRET_NAMES
            }
        }
    }
    return release, outputs, {"cloudfront_public_key": public}


class SecretTools:
    def __init__(self, release, outputs, private):
        self.release, self.outputs, self.private = release, outputs, private
        self.values = {key: None for key in initialization.SECRET_NAMES}
        self.calls, self.puts = [], []
        self.owner = release["accountId"]
        self.deleted, self.rotation = False, False
        self.bad_reference = False
        self.partial_metadata = False
        self.fail_action = None
        self.ambiguous_put = False
        self.concurrent = None
        self.descriptions = {}
        self.raw_run = initialization.run

    def api(self, region, service, action, *arguments):
        self.calls.append((service, action, list(arguments)))
        if self.fail_action == action:
            raise images.ReleaseError("Secret operation unavailable")
        if action == "get-caller-identity":
            return {"Account": self.owner}
        arn = arguments[1]
        key = arn.split("/")[1].rsplit("-", 1)[0]
        name = f"{self.release['application']}-{self.release['environment']}/{key}"
        if self.bad_reference:
            arn = arn.replace("123456789012", "999999999999")
        if action == "describe-secret":
            self.descriptions[key] = self.descriptions.get(key, 0) + 1
            if self.concurrent and self.descriptions[key] == 2:
                self.concurrent(key, self)
            stored = self.values[key]
            stages = {stored["version"]: ["AWSCURRENT"]} if stored else {}
            if self.partial_metadata:
                stages = {"f" * 64: ["AWSPREVIOUS"]}
            return {
                "ARN": arn,
                "Name": name,
                "DeletedDate": "pending" if self.deleted else None,
                "RotationEnabled": self.rotation,
                "VersionIdsToStages": stages,
            }
        if action == "get-secret-value":
            stored = self.values[key]
            return {
                "ARN": arn,
                "Name": name,
                "VersionId": stored["version"],
                "VersionStages": ["AWSCURRENT"],
                "SecretString": stored["value"],
            }
        raise AssertionError(f"Unexpected secret inspection: {action}")

    def run(self, command, *, stdin=None):
        if command[0] == "openssl":
            return self.raw_run(command, stdin=stdin)
        if command[:3] == ["aws", "secretsmanager", "put-secret-value"]:
            self.calls.append(("secretsmanager", "put-secret-value", command[3:]))
            self.assert_request(command, stdin)
            request = json.loads(stdin)
            key = request["SecretId"].split("/")[1].rsplit("-", 1)[0]
            self.puts.append(request)
            self.values[key] = {
                "version": request["ClientRequestToken"],
                "value": request["SecretString"],
            }
            if self.ambiguous_put:
                self.ambiguous_put = False
                raise images.ReleaseError("Initialization response unavailable")
            return json.dumps(
                {
                    "ARN": request["SecretId"],
                    "Name": f"{self.release['application']}-{self.release['environment']}/{key}",
                    "VersionId": request["ClientRequestToken"],
                    "VersionStages": ["AWSCURRENT"],
                }
            )
        raise AssertionError("Unexpected secret mutation command")

    def assert_request(self, command, stdin):
        if command[3:5] != ["--cli-input-json", "file:///dev/stdin"] or not stdin:
            raise AssertionError(
                "Secret values must be sent through native JSON on stdin"
            )
        if self.private in " ".join(command):
            raise AssertionError("Signing key leaked to process arguments")


class ReleaseSecretsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.private = images.run(
            [
                "openssl",
                "genpkey",
                "-algorithm",
                "RSA",
                "-pkeyopt",
                "rsa_keygen_bits:2048",
            ]
        )
        cls.public = initialization.public_key(cls.private)
        cls.other_private = images.run(
            [
                "openssl",
                "genpkey",
                "-algorithm",
                "RSA",
                "-pkeyopt",
                "rsa_keygen_bits:2048",
            ]
        )

    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.destination = Path(self.directory.name) / "initialization.json"
        self.release, self.outputs, self.variables = fixture(self.public)
        self.tools = SecretTools(self.release, self.outputs, self.private)

    def initialize(self, signing=None):
        with (
            patch.object(initialization, "api", self.tools.api),
            patch.object(initialization, "run", self.tools.run),
        ):
            return initialization.initialize(
                self.release,
                self.outputs,
                self.variables,
                self.destination,
                self.private if signing is None else signing,
            )

    def populate(self):
        for key in initialization.SECRET_NAMES:
            self.tools.values[key] = {
                "version": "d" * 64,
                "value": self.private
                if key == "media-signing"
                else "existing-" + "z" * 48 + "+/=",
            }

    def test_empty_secrets_are_initialized_with_native_stdin_requests_and_safe_receipts(
        self,
    ):
        result = self.initialize()
        self.assertEqual(result["status"], "SUCCEEDED")
        self.assertEqual(
            set(result["initializedSecrets"]), set(initialization.SECRET_NAMES)
        )
        self.assertEqual(len(self.tools.puts), 3)
        receipt = self.destination.read_text()
        self.assertEqual(json.loads(receipt), result)
        for key, stored in self.tools.values.items():
            self.assertNotIn(stored["value"], receipt)
            self.assertEqual(len(stored["version"]), 64)
            if key != "media-signing":
                self.assertRegex(stored["value"], r"^[A-Za-z0-9_-]{64}$")
        self.assertNotEqual(
            self.tools.values["database-app"]["value"],
            self.tools.values["web-session"]["value"],
        )
        self.assertEqual(self.destination.stat().st_mode & 0o777, 0o600)
        self.assertEqual(list(Path(self.directory.name).iterdir()), [self.destination])
        self.assertTrue(
            all("--secret-string" not in args for _, _, args in self.tools.calls)
        )

    def test_releases_preserve_existing_passwords_sessions_and_signing_keys(self):
        self.populate()
        previous = copy.deepcopy(self.tools.values)
        result = self.initialize(signing=self.other_private)
        self.assertEqual(result["initializedSecrets"], [])
        self.assertEqual(self.tools.values, previous)
        self.assertEqual(self.tools.puts, [])

    def test_empty_signing_secret_requires_a_matching_key_before_any_write(self):
        for private in ["", self.other_private, "invalid private key"]:
            with (
                self.subTest(private=private[:20]),
                self.assertRaises(images.ReleaseError),
            ):
                self.initialize(private)
            self.assertEqual(self.tools.puts, [])
            self.assertFalse(self.destination.exists())

    def test_existing_signing_secret_must_match_the_cloudfront_public_key(self):
        self.populate()
        self.tools.values["media-signing"]["value"] = self.other_private
        with self.assertRaisesRegex(images.ReleaseError, "must match"):
            self.initialize()
        self.assertEqual(self.tools.puts, [])

    def test_all_existing_values_are_validated_before_initializing_missing_ones(self):
        self.tools.values["web-session"] = {"version": "d" * 64, "value": "too-short"}
        with self.assertRaisesRegex(images.ReleaseError, "32 printable"):
            self.initialize()
        self.assertEqual(self.tools.puts, [])

    def test_public_keys_are_canonicalized_without_changing_the_signing_identity(self):
        self.variables["cloudfront_public_key"] = (
            self.public.replace("\n", "\r\n") + "\r\n"
        )
        self.assertEqual(self.initialize()["status"], "SUCCEEDED")

    def test_wrong_account_foreign_or_inactive_secret_metadata_never_writes_values(
        self,
    ):
        for field, value in [
            ("owner", "999999999999"),
            ("deleted", True),
            ("rotation", True),
            ("bad_reference", True),
            ("partial_metadata", True),
            ("fail_action", "describe-secret"),
        ]:
            with self.subTest(field=field):
                self.tools = SecretTools(self.release, self.outputs, self.private)
                setattr(self.tools, field, value)
                with self.assertRaises(images.ReleaseError):
                    self.initialize()
                self.assertEqual(self.tools.puts, [])

    def test_an_ambiguous_first_write_is_recovered_without_rotating_that_secret(self):
        self.tools.ambiguous_put = True
        with self.assertRaises(images.ReleaseError):
            self.initialize()
        self.assertFalse(self.destination.exists())
        previous = copy.deepcopy(self.tools.values["media-signing"])
        result = self.initialize()
        self.assertEqual(result["initializedSecrets"], ["database-app", "web-session"])
        self.assertEqual(self.tools.values["media-signing"], previous)
        self.assertEqual(len(self.tools.puts), 3)

    def test_a_concurrent_initialization_is_reused_instead_of_overwritten(self):
        def concurrent(key, tools):
            tools.values[key] = {
                "version": "e" * 64,
                "value": self.private
                if key == "media-signing"
                else "concurrent-" + "q" * 48,
            }

        self.tools.concurrent = concurrent
        result = self.initialize()
        self.assertEqual(self.tools.puts, [])
        self.assertEqual(result["initializedSecrets"], [])
        self.assertEqual(set(result["secretVersions"].values()), {"e" * 64})

    def test_a_later_rotation_cannot_be_mistaken_for_a_confirmed_initialization(self):
        original = self.tools.api

        def rotated(region, service, action, *args):
            if (
                action == "describe-secret"
                and self.tools.descriptions.get("media-signing", 0) == 2
            ):
                self.tools.values["media-signing"] = {
                    "version": "e" * 64,
                    "value": self.other_private,
                }
            return original(region, service, action, *args)

        with (
            patch.object(self.tools, "api", rotated),
            self.assertRaisesRegex(images.ReleaseError, "changed"),
        ):
            self.initialize()
        self.assertFalse(self.destination.exists())

    def test_missing_or_foreign_references_are_rejected_before_requesting_credentials(
        self,
    ):
        for alter in [
            lambda refs: refs.pop("web-session"),
            lambda refs: refs.update(master="rds-password"),
            lambda refs: refs.update(
                **{
                    "database-app": refs["database-app"].replace(
                        "closetos-dev", "closetos-prod"
                    )
                }
            ),
            lambda refs: refs.update(
                **{
                    "web-session": "arn:aws:secretsmanager:eu-west-2:123456789012:secret:closetos-dev/web-session-abcdef-trailing"
                }
            ),
        ]:
            outputs = copy.deepcopy(self.outputs)
            alter(outputs["application_secret_arns"]["value"])
            with (
                patch.object(initialization, "api", self.tools.api),
                self.assertRaises(images.ReleaseError),
            ):
                initialization.initialize(
                    self.release,
                    outputs,
                    self.variables,
                    self.destination,
                    self.private,
                )
            self.assertEqual(self.tools.calls, [])

    def test_existing_receipts_are_never_reused_or_overwritten(self):
        self.destination.write_text("previous")
        with self.assertRaisesRegex(images.ReleaseError, "fresh"):
            self.initialize()
        self.assertEqual(self.tools.calls, [])

    def test_non_rsa_and_wrong_size_signing_keys_are_rejected(self):
        for arguments in [
            ["-algorithm", "ED25519"],
            ["-algorithm", "RSA", "-pkeyopt", "rsa_keygen_bits:1024"],
        ]:
            private = images.run(["openssl", "genpkey", *arguments])
            with self.assertRaises(images.ReleaseError):
                initialization.public_key(private)

    def test_the_cli_keeps_secret_values_off_arguments_stdout_and_stderr_on_failure(
        self,
    ):
        root = Path(self.directory.name)
        for key, value in [
            ("infrastructure", self.outputs),
            ("variables", self.variables),
        ]:
            (root / (key + ".json")).write_text(json.dumps(value))
        stub = root / "aws"
        stub.write_text(
            "#!/usr/bin/env python3\nimport os, sys\nprint(os.environ['MEDIA_SIGNING_PRIVATE_KEY'])\nprint(os.environ['MEDIA_SIGNING_PRIVATE_KEY'], file=sys.stderr)\nsys.exit(1)\n"
        )
        stub.chmod(0o700)
        arguments = [
            sys.executable,
            str(Path(initialization.__file__).resolve()),
            "initialize",
            "--account",
            self.release["accountId"],
            "--region",
            self.release["region"],
            "--source",
            self.release["sourceCommit"],
            "--publication",
            self.release["publicationId"],
            "--infrastructure",
            str(root / "infrastructure.json"),
            "--variables",
            str(root / "variables.json"),
            "--destination",
            str(self.destination),
        ]
        environment = {
            key: value
            for key, value in os.environ.items()
            if not key.startswith("AWS_")
        }
        result = subprocess.run(
            arguments,
            env={
                **environment,
                "PATH": str(root) + os.pathsep + environment["PATH"],
                "MEDIA_SIGNING_PRIVATE_KEY": self.private,
            },
            check=False,
            capture_output=True,
            text=True,
            timeout=30,
        )
        self.assertEqual(result.returncode, 1)
        self.assertNotIn(self.private, result.stdout + result.stderr)
        self.assertNotIn("BEGIN PRIVATE KEY", result.stdout + result.stderr)
        self.assertIn("Release command failed: aws sts", result.stderr)
        self.assertFalse(self.destination.exists())


if __name__ == "__main__":
    unittest.main()
