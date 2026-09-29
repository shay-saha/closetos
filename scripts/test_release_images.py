import contextlib
import io
import json
import os
import subprocess
import sys
import tempfile
import traceback
import unittest
from pathlib import Path
from unittest.mock import patch

import release_images as release

IMAGE = "sha256:" + "a" * 64
MANIFEST = "sha256:" + "b" * 64
SOURCE = "c" * 40
TOKEN = "private-registry-login-token"


class RegistryTools:
    def __init__(self, metadata):
        self.metadata = metadata
        self.calls = []
        self.account = metadata["accountId"]
        self.revision = metadata["sourceCommit"]
        self.immutable = True
        self.image = IMAGE
        self.architecture = "amd64"
        self.reported_digest = MANIFEST
        self.registry_digest = MANIFEST
        self.fail = None

    def __call__(self, command, *, stdin=None):
        self.calls.append((command, stdin))
        if self.fail and command[: len(self.fail)] == self.fail:
            raise release.ReleaseError("Simulated tool failure")
        if command[:3] == ["docker", "image", "inspect"]:
            return json.dumps(
                [
                    {
                        "Id": self.image,
                        "Os": "linux",
                        "Architecture": self.architecture,
                        "Config": {
                            "Labels": {
                                "org.opencontainers.image.revision": self.revision
                            }
                        },
                    }
                ]
            )
        if command[:3] == ["aws", "sts", "get-caller-identity"]:
            return json.dumps({"Account": self.account})
        if command[:3] == ["aws", "ecr", "describe-repositories"]:
            name = command[command.index("--repository-names") + 1]
            service = name.split("/")[-1]
            return json.dumps(
                {
                    "repositories": [
                        {
                            "repositoryName": name,
                            "repositoryUri": release.repository_uri(
                                self.metadata, service
                            ),
                            "imageTagMutability": "IMMUTABLE"
                            if self.immutable
                            else "MUTABLE",
                        }
                    ]
                }
            )
        if command[:3] == ["aws", "ecr", "get-login-password"]:
            return TOKEN + "\n"
        if command[:2] in [
            ["docker", "tag"],
            ["docker", "login"],
            ["docker", "logout"],
        ]:
            return ""
        if command[:2] == ["docker", "push"]:
            return f"Pushed\nrelease: digest: {self.reported_digest} size: 1234\n"
        if command[:3] == ["aws", "ecr", "describe-images"]:
            tag = command[command.index("--image-ids") + 1].removeprefix("imageTag=")
            return json.dumps(
                {
                    "imageDetails": [
                        {"imageDigest": self.registry_digest, "imageTags": [tag]}
                    ]
                }
            )
        raise AssertionError(f"Unexpected external command: {command}")


class ReleaseImagesTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        self.metadata = release.metadata(
            "123456789012", "eu-west-2", "closetos", "dev", SOURCE, "9876-2"
        )
        self.scan = self.directory / "scan.json"
        self.report = {
            "SchemaVersion": 2,
            "ArtifactName": IMAGE,
            "ArtifactType": "container_image",
            "Metadata": {"ImageID": IMAGE},
            "Results": [{"Type": "debian"}, {"Type": "jar"}],
        }
        self.scan.write_text(json.dumps(self.report))
        self.destination = self.directory / "api.json"
        self.tools = RegistryTools(self.metadata)

    def publish(self, service="api", destination=None):
        with patch.object(release, "run", self.tools):
            return release.publish(
                self.metadata,
                service,
                IMAGE,
                self.scan,
                destination or self.destination,
            )

    def test_publishes_the_scanned_image_and_records_the_confirmed_digest(self):
        result = self.publish()
        self.assertEqual(result, json.loads(self.destination.read_text()))
        self.assertEqual(result["sourceCommit"], SOURCE)
        self.assertEqual(result["imageDigest"], MANIFEST)
        self.assertEqual(
            result["imageReference"],
            f"123456789012.dkr.ecr.eu-west-2.amazonaws.com/closetos-dev/api@{MANIFEST}",
        )
        commands = [command for command, _ in self.tools.calls]
        tagged = next(
            command for command in commands if command[:2] == ["docker", "tag"]
        )
        self.assertEqual(tagged[2], IMAGE)
        self.assertTrue(tagged[3].endswith(f":{SOURCE}-9876-2"))
        login = next(
            call for call in self.tools.calls if call[0][:2] == ["docker", "login"]
        )
        self.assertEqual(login[1], TOKEN + "\n")
        self.assertNotIn(TOKEN, json.dumps(commands))
        self.assertEqual(commands[-1][:2], ["docker", "logout"])
        self.assertEqual(self.destination.stat().st_mode & 0o777, 0o600)

    def test_rejects_critical_vulnerabilities_before_any_external_command(self):
        self.report["Results"][0]["Vulnerabilities"] = [{"Severity": "CRITICAL"}]
        self.scan.write_text(json.dumps(self.report))
        with self.assertRaisesRegex(release.ReleaseError, "Critical"):
            self.publish()
        self.assertEqual(self.tools.calls, [])
        self.assertFalse(self.destination.exists())

    def test_rejects_a_scan_of_another_image(self):
        self.report["Metadata"]["ImageID"] = "sha256:" + "d" * 64
        self.scan.write_text(json.dumps(self.report))
        with self.assertRaisesRegex(release.ReleaseError, "exact image"):
            self.publish()
        self.assertEqual(self.tools.calls, [])

    def test_rejects_missing_incomplete_and_invalid_scan_reports(self):
        for value in [
            None,
            {},
            {**self.report, "Results": []},
            {**self.report, "Results": [{}]},
            {**self.report, "Results": [{"Type": "debian", "Vulnerabilities": None}]},
        ]:
            with self.subTest(value=value):
                self.scan.write_text(json.dumps(value))
                with self.assertRaises(release.ReleaseError):
                    self.publish()
        self.scan.unlink()
        with self.assertRaises(release.ReleaseError):
            self.publish()
        self.assertEqual(self.tools.calls, [])

    def test_rejects_a_different_source_commit_or_image_platform_before_aws(self):
        for field, value in [
            ("revision", "d" * 40),
            ("image", MANIFEST),
            ("architecture", "arm64"),
        ]:
            with self.subTest(field=field):
                self.tools = RegistryTools(self.metadata)
                setattr(self.tools, field, value)
                with self.assertRaisesRegex(release.ReleaseError, "Linux amd64"):
                    self.publish()
                self.assertFalse(
                    any(command[0] == "aws" for command, _ in self.tools.calls)
                )

    def test_rejects_the_wrong_account_before_registry_login(self):
        self.tools.account = "999999999999"
        with self.assertRaisesRegex(release.ReleaseError, "another AWS account"):
            self.publish()
        self.assertFalse(
            any(command[:2] == ["docker", "login"] for command, _ in self.tools.calls)
        )

    def test_rejects_a_mutable_repository_before_pushing(self):
        self.tools.immutable = False
        with self.assertRaisesRegex(release.ReleaseError, "immutable tags"):
            self.publish()
        self.assertFalse(
            any(command[:2] == ["docker", "push"] for command, _ in self.tools.calls)
        )

    def test_docker_and_registry_must_agree_before_writing_an_artifact(self):
        self.tools.registry_digest = "sha256:" + "d" * 64
        with self.assertRaisesRegex(release.ReleaseError, "ECR did not confirm"):
            self.publish()
        self.assertFalse(self.destination.exists())
        self.assertEqual(self.tools.calls[-1][0][:2], ["docker", "logout"])

    def test_a_push_without_a_manifest_digest_never_produces_a_release(self):
        self.tools.reported_digest = "latest"
        with self.assertRaisesRegex(release.ReleaseError, "single pushed manifest"):
            self.publish()
        self.assertFalse(self.destination.exists())
        self.assertEqual(self.tools.calls[-1][0][:2], ["docker", "logout"])

    def test_login_push_and_confirmation_failures_always_remove_local_credentials(self):
        for failure in [
            ["docker", "login"],
            ["docker", "push"],
            ["aws", "ecr", "describe-images"],
        ]:
            with self.subTest(failure=failure):
                self.tools = RegistryTools(self.metadata)
                self.tools.fail = failure
                with self.assertRaises(release.ReleaseError):
                    self.publish()
                self.assertEqual(self.tools.calls[-1][0][:2], ["docker", "logout"])
                self.assertFalse(self.destination.exists())

    def test_an_existing_artifact_is_never_reused_or_overwritten(self):
        self.destination.write_text("earlier release")
        with self.assertRaisesRegex(release.ReleaseError, "already exists"):
            self.publish()
        self.assertEqual(self.tools.calls, [])
        self.assertEqual(self.destination.read_text(), "earlier release")

    def test_collects_all_three_confirmed_images(self):
        for service in release.SERVICES:
            self.publish(service, self.directory / f"{service}.json")
        destination = self.directory / "release.json"
        result = release.collect(self.metadata, self.directory, destination)
        self.assertEqual(set(result["images"]), release.SERVICES)
        self.assertEqual(result, json.loads(destination.read_text()))

    def test_incomplete_or_mixed_releases_are_never_collected(self):
        destination = self.directory / "release.json"
        with self.assertRaises(release.ReleaseError):
            release.collect(self.metadata, self.directory, destination)
        for service in release.SERVICES:
            self.publish(service, self.directory / f"{service}.json")
        api_file = self.directory / "api.json"
        confirmed_api = json.loads(api_file.read_text())
        for field, value in [
            ("sourceCommit", "d" * 40),
            ("environment", "prod"),
            ("publicationId", "9876-1"),
            ("service", "web"),
            ("imageDigest", "latest"),
            ("imageReference", f"other.example/api@{MANIFEST}"),
        ]:
            with self.subTest(field=field):
                api_file.write_text(json.dumps({**confirmed_api, field: value}))
                with self.assertRaises(release.ReleaseError):
                    release.collect(self.metadata, self.directory, destination)
                self.assertFalse(destination.exists())
        api_file.write_text(json.dumps(confirmed_api))

    def test_failed_tool_output_never_exposes_registry_credentials(self):
        failed = subprocess.CalledProcessError(
            1, ["docker", "login"], output=TOKEN, stderr=TOKEN
        )
        with patch.object(release.subprocess, "run", side_effect=failed):
            try:
                release.run(["docker", "login"], stdin=TOKEN)
            except release.ReleaseError as exception:
                formatted = "".join(traceback.format_exception(exception))
                self.assertNotIn(TOKEN, formatted)
                self.assertIn("docker login", str(exception))
            else:
                self.fail("A failed command was accepted")

    def test_validation_rejects_injected_or_ambiguous_release_identifiers(self):
        for field, value in [
            (0, "123456789012; echo invalid"),
            (1, "eu-west-2/other"),
            (2, "closetos/other"),
            (3, "staging"),
            (4, "main"),
            (5, "9876-2/other"),
        ]:
            args = ["123456789012", "eu-west-2", "closetos", "dev", SOURCE, "9876-2"]
            args[field] = value
            with self.subTest(field=field), self.assertRaises(release.ReleaseError):
                release.metadata(*args)

    def test_configuration_check_does_not_contact_aws_or_docker(self):
        arguments = [
            "release_images.py",
            "check",
            "--account",
            "123456789012",
            "--region",
            "eu-west-2",
            "--source",
            SOURCE,
            "--publication",
            "9876-2",
            "--role",
            "arn:aws:iam::123456789012:role/closetos-dev-github-publisher",
        ]
        with (
            patch("sys.argv", arguments),
            patch.object(release, "run") as tools,
            contextlib.redirect_stdout(io.StringIO()),
        ):
            release.main()
            tools.assert_not_called()
        arguments[-1] = "arn:aws:iam::123456789012:role/administrator"
        with (
            patch("sys.argv", arguments),
            contextlib.redirect_stderr(io.StringIO()),
            self.assertRaises(SystemExit) as failure,
        ):
            release.main()
        self.assertEqual(failure.exception.code, 1)

    def test_actual_cli_publishes_through_local_tool_processes_without_credentials(
        self,
    ):
        executables = self.directory / "bin"
        executables.mkdir()
        stub = executables / "registry-tool"
        scripts = Path(__file__).resolve().parent
        commands_file = self.directory / "commands.json"
        stub.write_text(
            f"#!{sys.executable}\n"
            "import json, sys\n"
            "from pathlib import Path\n"
            f"sys.path.insert(0, {str(scripts)!r})\n"
            "from test_release_images import RegistryTools, SOURCE\n"
            "from release_images import metadata\n"
            "command = [Path(sys.argv[0]).name, *sys.argv[1:]]\n"
            "stdin = sys.stdin.read() if command[0:2] == ['docker', 'login'] else None\n"
            f"log = Path({str(commands_file)!r})\n"
            "calls = json.loads(log.read_text()) if log.exists() else []\n"
            "calls.append(command)\n"
            "log.write_text(json.dumps(calls))\n"
            "tools = RegistryTools(metadata('123456789012', 'eu-west-2', 'closetos', 'dev', SOURCE, '9876-2'))\n"
            "print(tools(command, stdin=stdin))\n"
        )
        stub.chmod(0o755)
        for tool in ["aws", "docker"]:
            (executables / tool).symlink_to(stub)
        environment = {
            **os.environ,
            "PATH": str(executables) + os.pathsep + os.environ.get("PATH", ""),
        }
        environment = {
            key: value
            for key, value in environment.items()
            if not key.startswith("AWS_")
        }
        result = subprocess.run(
            [
                sys.executable,
                str(scripts / "release_images.py"),
                "publish",
                "--account",
                "123456789012",
                "--region",
                "eu-west-2",
                "--source",
                SOURCE,
                "--publication",
                "9876-2",
                "--service",
                "api",
                "--image",
                IMAGE,
                "--scan",
                str(self.scan),
                "--destination",
                str(self.destination),
            ],
            capture_output=True,
            text=True,
            check=False,
            timeout=30,
            env=environment,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertNotIn(TOKEN, result.stdout + result.stderr)
        artifact = json.loads(self.destination.read_text())
        self.assertEqual(artifact["localImageId"], IMAGE)
        self.assertEqual(artifact["imageDigest"], MANIFEST)
        commands = json.loads(commands_file.read_text())
        self.assertEqual(commands[-1][:2], ["docker", "logout"])
        self.assertTrue(any(command[:2] == ["docker", "push"] for command in commands))


if __name__ == "__main__":
    unittest.main()
