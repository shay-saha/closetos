import argparse
import json
import os
import re
import subprocess
import tempfile
from pathlib import Path

SERVICES = frozenset({"api", "web", "media-worker"})
DIGEST = re.compile(r"sha256:[0-9a-f]{64}")
COMMIT = re.compile(r"[0-9a-f]{40}")


class ReleaseError(Exception):
    pass


def require(condition, message):
    if not condition:
        raise ReleaseError(message)


def read_json(path):
    try:
        return json.loads(Path(path).read_text())
    except (OSError, ValueError):
        raise ReleaseError(
            "A required release or scan file is missing or invalid."
        ) from None


def run(command, *, stdin=None):
    try:
        result = subprocess.run(
            command,
            input=stdin,
            capture_output=True,
            text=True,
            check=True,
            timeout=900,
            env={**os.environ, "AWS_PAGER": "", "AWS_CLI_AUTO_PROMPT": "off"},
        )
        return result.stdout
    except (OSError, subprocess.SubprocessError):
        # Tool output may contain registry credentials; never include it in release failures.
        raise ReleaseError(
            f"Release command failed: {command[0]} {command[1]}"
        ) from None


def aws(region, *arguments):
    response = run(
        ["aws", *arguments, "--region", region, "--no-cli-pager", "--output", "json"]
    )
    try:
        parsed = json.loads(response)
    except ValueError:
        raise ReleaseError("AWS returned an invalid release response.") from None
    require(isinstance(parsed, dict), "AWS returned an invalid release response.")
    return parsed


def metadata(account, region, application, environment, source, publication):
    require(re.fullmatch(r"[0-9]{12}", account), "Choose an exact AWS account ID.")
    require(
        re.fullmatch(r"[a-z]{2}-[a-z]+-[0-9]", region),
        "Choose a commercial AWS region.",
    )
    require(
        re.fullmatch(r"[a-z][a-z0-9-]{2,19}", application),
        "Choose a valid application name.",
    )
    require(environment in {"dev", "prod"}, "Choose dev or prod.")
    require(COMMIT.fullmatch(source), "Publish a complete source commit SHA.")
    require(
        re.fullmatch(r"[1-9][0-9]*-[1-9][0-9]*", publication),
        "Provide the workflow run and attempt IDs.",
    )
    return {
        "schemaVersion": 1,
        "accountId": account,
        "region": region,
        "application": application,
        "environment": environment,
        "sourceCommit": source,
        "publicationId": publication,
    }


def repository_uri(release, service):
    return (
        f"{release['accountId']}.dkr.ecr.{release['region']}.amazonaws.com/"
        f"{release['application']}-{release['environment']}/{service}"
    )


def validate_scan(report, image):
    require(isinstance(report, dict), "The image scan report is invalid.")
    require(
        report.get("SchemaVersion") == 2
        and report.get("ArtifactType") == "container_image"
        and report.get("ArtifactName") == image
        and isinstance(report.get("Metadata"), dict)
        and report["Metadata"].get("ImageID") == image,
        "The scan must describe the exact image being published.",
    )
    results = report.get("Results")
    require(
        isinstance(results, list) and len(results) > 0,
        "The image scan did not report any scanned packages.",
    )
    for result in results:
        require(
            isinstance(result, dict) and result.get("Type"),
            "An image scan result is incomplete.",
        )
        vulnerabilities = result.get("Vulnerabilities", [])
        require(
            isinstance(vulnerabilities, list),
            "Image vulnerability results are invalid.",
        )
        for vulnerability in vulnerabilities:
            require(
                isinstance(vulnerability, dict)
                and vulnerability.get("Severity")
                in {"UNKNOWN", "LOW", "MEDIUM", "HIGH", "CRITICAL"},
                "An image vulnerability has no recognized severity.",
            )
            require(
                vulnerability["Severity"] != "CRITICAL",
                "Critical image vulnerabilities prevent publication.",
            )


def write_json(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    require(not path.exists(), "A release artifact already exists at this destination.")
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(
            mode="w", dir=path.parent, delete=False
        ) as output:
            temporary = Path(output.name)
            json.dump(value, output, sort_keys=True, indent=2)
            output.write("\n")
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, path)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def publish(release, service, image, scan, destination):
    require(service in SERVICES, "Choose api, web, or media-worker.")
    require(DIGEST.fullmatch(image), "Publish an immutable local image ID.")
    require(
        not Path(destination).exists(),
        "A release artifact already exists at this destination.",
    )
    validate_scan(read_json(scan), image)
    try:
        images = json.loads(run(["docker", "image", "inspect", image]))
    except ValueError:
        raise ReleaseError("Docker returned invalid image metadata.") from None
    require(
        isinstance(images, list) and len(images) == 1 and isinstance(images[0], dict),
        "Docker did not return the exact local image.",
    )
    inspected = images[0]
    configuration = inspected.get("Config")
    require(
        isinstance(configuration, dict), "Docker did not return image configuration."
    )
    labels = configuration.get("Labels") or {}
    require(isinstance(labels, dict), "Docker did not return image labels.")
    require(
        inspected.get("Id") == image
        and inspected.get("Os") == "linux"
        and inspected.get("Architecture") == "amd64"
        and labels.get("org.opencontainers.image.revision") == release["sourceCommit"],
        "The scanned image must be a Linux amd64 build of this source commit.",
    )
    region = release["region"]
    require(
        aws(region, "sts", "get-caller-identity").get("Account")
        == release["accountId"],
        "The publishing credentials belong to another AWS account.",
    )
    name = f"{release['application']}-{release['environment']}/{service}"
    uri = repository_uri(release, service)
    repositories = aws(
        region, "ecr", "describe-repositories", "--repository-names", name
    ).get("repositories", [])
    require(
        isinstance(repositories, list)
        and len(repositories) == 1
        and isinstance(repositories[0], dict)
        and repositories[0].get("repositoryName") == name
        and repositories[0].get("repositoryUri") == uri
        and repositories[0].get("imageTagMutability") == "IMMUTABLE",
        "The environment's ECR repository must use immutable tags.",
    )
    tag = f"{release['sourceCommit']}-{release['publicationId']}"
    reference = f"{uri}:{tag}"
    registry = uri.split("/")[0]
    run(["docker", "tag", image, reference])
    password = run(
        ["aws", "ecr", "get-login-password", "--region", region, "--no-cli-pager"]
    ).strip()
    require(bool(password), "ECR did not return a registry login token.")
    try:
        run(
            ["docker", "login", "--username", "AWS", "--password-stdin", registry],
            stdin=password + "\n",
        )
        pushed = run(["docker", "push", reference])
        digests = re.findall(r"digest: (sha256:[0-9a-f]{64}) size: [0-9]+", pushed)
        require(
            len(digests) == 1, "Docker did not confirm a single pushed manifest digest."
        )
        digest = digests[0]
        details = aws(
            region,
            "ecr",
            "describe-images",
            "--repository-name",
            name,
            "--image-ids",
            f"imageTag={tag}",
        ).get("imageDetails", [])
        require(
            isinstance(details, list)
            and len(details) == 1
            and isinstance(details[0], dict)
            and details[0].get("imageDigest") == digest
            and tag in details[0].get("imageTags", []),
            "ECR did not confirm the manifest digest reported by Docker.",
        )
    finally:
        run(["docker", "logout", registry])
    artifact = {
        **release,
        "service": service,
        "localImageId": image,
        "imageDigest": digest,
        "imageReference": f"{uri}@{digest}",
    }
    write_json(destination, artifact)
    return artifact


def validate_manifest(manifest, release):
    require(isinstance(manifest, dict), "The release manifest is invalid.")
    require(
        all(manifest.get(key) == value for key, value in release.items()),
        "The release manifest belongs to another commit, publication, or environment.",
    )
    artifacts = manifest.get("images")
    require(
        isinstance(artifacts, dict) and set(artifacts) == SERVICES,
        "A release must contain all three published images.",
    )
    for service, artifact in artifacts.items():
        require(isinstance(artifact, dict), "A published image artifact is invalid.")
        require(
            all(artifact.get(key) == value for key, value in release.items())
            and artifact.get("service") == service,
            "All release images must belong to the same commit, publication, and environment.",
        )
        digest = artifact.get("imageDigest", "")
        image = artifact.get("localImageId", "")
        require(
            isinstance(digest, str)
            and DIGEST.fullmatch(digest)
            and isinstance(image, str)
            and DIGEST.fullmatch(image)
            and artifact.get("imageReference")
            == f"{repository_uri(release, service)}@{digest}",
            "A published image must identify an exact digest in its environment's repository.",
        )
    return manifest


def collect(release, directory, destination):
    manifest = validate_manifest(
        {
            **release,
            "images": {
                service: read_json(Path(directory) / f"{service}.json")
                for service in SERVICES
            },
        },
        release,
    )
    write_json(destination, manifest)
    return manifest


def main():
    parser = argparse.ArgumentParser(
        description="Publish scanned images and collect an immutable release manifest."
    )
    parser.add_argument("action", choices=["check", "publish", "collect"])
    parser.add_argument("--account", required=True)
    parser.add_argument("--region", required=True)
    parser.add_argument("--application", default="closetos")
    parser.add_argument("--environment", choices=["dev", "prod"], default="dev")
    parser.add_argument("--source", required=True)
    parser.add_argument("--publication", required=True)
    parser.add_argument("--destination")
    parser.add_argument("--role")
    parser.add_argument("--service", choices=sorted(SERVICES))
    parser.add_argument("--image")
    parser.add_argument("--scan")
    parser.add_argument("--directory")
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
            require(
                args.role
                == f"arn:aws:iam::{args.account}:role/{args.application}-{args.environment}-github-publisher",
                "Choose the environment's dedicated GitHub publishing role.",
            )
            print("Release configuration verified.")
            return
        require(args.destination, "Provide a destination for the release artifact.")
        if args.action == "publish":
            require(
                args.service and args.image and args.scan,
                "Publishing requires a service, image ID, and scan report.",
            )
            publish(release, args.service, args.image, args.scan, args.destination)
        else:
            require(args.directory, "Collecting requires an artifact directory.")
            collect(release, args.directory, args.destination)
        print("Verified release artifact written.")
    except ReleaseError as exception:
        parser.exit(1, f"{exception}\n")


if __name__ == "__main__":
    main()
