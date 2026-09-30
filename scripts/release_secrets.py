import argparse
import hashlib
import json
import os
import re
import secrets
from pathlib import Path

from release_images import ReleaseError, metadata, read_json, require, run, write_json
from release_migration import api, output

SECRET_NAMES = ("media-signing", "database-app", "web-session")


def canonical_public_key(public):
    canonical = run(["openssl", "pkey", "-pubin", "-pubout"], stdin=public).strip()
    details = run(["openssl", "rsa", "-pubin", "-text", "-noout"], stdin=canonical)
    require(
        "Public-Key: (2048 bit)" in details,
        "CloudFront signing requires an RSA-2048 key.",
    )
    return canonical


def public_key(private):
    require(
        isinstance(private, str)
        and private.strip().startswith("-----BEGIN PRIVATE KEY-----"),
        "Use an unencrypted PKCS#8 RSA-2048 signing key.",
    )
    public = run(["openssl", "pkey", "-pubout"], stdin=private).strip()
    return canonical_public_key(public)


def context(release, outputs, variables):
    require(
        isinstance(variables, dict),
        "Provide public Terraform configuration as a JSON object.",
    )
    public = variables.get("cloudfront_public_key")
    require(
        isinstance(public, str)
        and public.strip().startswith("-----BEGIN PUBLIC KEY-----"),
        "Provide the public CloudFront signing key used in Terraform.",
    )
    name = f"{release['application']}-{release['environment']}"
    references = output(outputs, "application_secret_arns")
    require(
        isinstance(references, dict) and set(references) == set(SECRET_NAMES),
        "Initialize exactly the three application secrets.",
    )
    for key, arn in references.items():
        prefix = f"arn:aws:secretsmanager:{release['region']}:{release['accountId']}:secret:{name}/{key}-"
        require(
            isinstance(arn, str)
            and re.fullmatch(re.escape(prefix) + r"[A-Za-z0-9]{6}", arn),
            "Application secrets must belong to this exact AWS account and environment.",
        )
    return {
        "name": name,
        "references": references,
        "publicKey": canonical_public_key(public),
    }


def current_version(release, target, key):
    arn = target["references"][key]
    description = api(
        release["region"], "secretsmanager", "describe-secret", "--secret-id", arn
    )
    require(
        description.get("ARN") == arn
        and description.get("Name") == f"{target['name']}/{key}"
        and not description.get("DeletedDate")
        and not description.get("RotationEnabled"),
        "Application secrets must be active, owned by this environment, and outside automatic rotation.",
    )
    stages = description.get("VersionIdsToStages", {})
    require(
        isinstance(stages, dict)
        and all(
            isinstance(version, str)
            and re.fullmatch(r"[A-Za-z0-9-]{32,64}", version)
            and isinstance(labels, list)
            and all(isinstance(label, str) for label in labels)
            for version, labels in stages.items()
        ),
        "Secret version metadata is invalid.",
    )
    current = [version for version, labels in stages.items() if "AWSCURRENT" in labels]
    require(
        len(current) <= 1 and (current or not stages),
        "A populated secret without one current version requires recovery before release.",
    )
    return current[0] if current else None


def read_secret(release, target, key, version):
    arn = target["references"][key]
    response = api(
        release["region"],
        "secretsmanager",
        "get-secret-value",
        "--secret-id",
        arn,
        "--version-id",
        version,
        "--version-stage",
        "AWSCURRENT",
    )
    require(
        response.get("ARN") == arn
        and response.get("Name") == f"{target['name']}/{key}"
        and response.get("VersionId") == version
        and "AWSCURRENT" in response.get("VersionStages", [])
        and isinstance(response.get("SecretString"), str)
        and not response.get("SecretBinary"),
        "The application secret's current value could not be confirmed.",
    )
    return response["SecretString"]


def validate_value(target, key, value):
    if key == "media-signing":
        require(
            public_key(value) == target["publicKey"],
            "The signing secret must match Terraform's CloudFront public key.",
        )
    else:
        require(
            isinstance(value, str) and re.fullmatch(r"[\x21-\x7e]{32,256}", value),
            "Application password and session secrets require at least 32 printable characters without whitespace.",
        )


def initial_token(arn):
    return hashlib.sha256(("closetos-initial-secret-v1:" + arn).encode()).hexdigest()


def put_initial_value(release, target, key, value):
    arn = target["references"][key]
    request = {
        "SecretId": arn,
        "ClientRequestToken": initial_token(arn),
        "SecretString": value,
        "VersionStages": ["AWSCURRENT"],
    }
    # stdin keeps credential values out of command arguments, temporary files, and logs.
    raw = run(
        [
            "aws",
            "secretsmanager",
            "put-secret-value",
            "--cli-input-json",
            "file:///dev/stdin",
            "--region",
            release["region"],
            "--cli-connect-timeout",
            "5",
            "--cli-read-timeout",
            "20",
            "--no-cli-pager",
            "--output",
            "json",
        ],
        stdin=json.dumps(request),
    )
    try:
        response = json.loads(raw)
    except ValueError:
        raise ReleaseError(
            "Secrets Manager returned an invalid initialization response."
        ) from None
    require(
        isinstance(response, dict)
        and response.get("ARN") == arn
        and response.get("VersionId") == request["ClientRequestToken"]
        and response.get("Name") == f"{target['name']}/{key}"
        and "AWSCURRENT" in response.get("VersionStages", []),
        "Secrets Manager did not confirm the initial application secret version.",
    )
    return request["ClientRequestToken"]


def initialize(release, outputs, variables, destination, signing_key=None):
    target = context(release, outputs, variables)
    require(
        not Path(destination).exists(),
        "Use a fresh secret initialization receipt destination.",
    )
    require(
        api(release["region"], "sts", "get-caller-identity").get("Account")
        == release["accountId"],
        "Initialization credentials belong to another AWS account.",
    )
    versions, values = {}, {}
    for key in SECRET_NAMES:
        version = current_version(release, target, key)
        versions[key] = version
        if version is not None:
            value = read_secret(release, target, key, version)
        elif key == "media-signing":
            value = signing_key
        else:
            value = secrets.token_urlsafe(48)
        validate_value(target, key, value)
        values[key] = value
    initialized = []
    for key in SECRET_NAMES:
        if versions[key] is not None:
            continue
        # Recheck before writing; a failed or concurrent first attempt may already have succeeded.
        observed = current_version(release, target, key)
        if observed is not None:
            validate_value(target, key, read_secret(release, target, key, observed))
            versions[key] = observed
        else:
            versions[key] = put_initial_value(release, target, key, values[key])
            initialized.append(key)
    for key in SECRET_NAMES:
        require(
            current_version(release, target, key) == versions[key],
            "The application secret's current version changed during initialization.",
        )
        validate_value(target, key, read_secret(release, target, key, versions[key]))
    result = {
        **release,
        "status": "SUCCEEDED",
        "secretVersions": versions,
        "initializedSecrets": initialized,
    }
    write_json(destination, result)
    return result


def main():
    parser = argparse.ArgumentParser(
        description="Initialize empty application secrets without rotating existing release credentials."
    )
    parser.add_argument("action", choices=["check", "initialize"])
    parser.add_argument("--account", required=True)
    parser.add_argument("--region", required=True)
    parser.add_argument("--application", default="closetos")
    parser.add_argument("--environment", choices=["dev", "prod"], default="dev")
    parser.add_argument("--source", required=True)
    parser.add_argument("--publication", required=True)
    parser.add_argument("--infrastructure", required=True)
    parser.add_argument("--variables", required=True)
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
        outputs, variables = read_json(args.infrastructure), read_json(args.variables)
        context(release, outputs, variables)
        if args.action == "check":
            print(
                "Application secret references and public signing configuration verified."
            )
            return
        require(
            args.destination, "Provide a secret initialization receipt destination."
        )
        initialize(
            release,
            outputs,
            variables,
            args.destination,
            os.environ.get("MEDIA_SIGNING_PRIVATE_KEY"),
        )
        print("Application secrets verified; existing credentials were preserved.")
    except (ReleaseError, OSError) as exception:
        parser.exit(
            1,
            f"{exception if isinstance(exception, ReleaseError) else 'A secret initialization receipt could not be written.'}\n",
        )


if __name__ == "__main__":
    main()
