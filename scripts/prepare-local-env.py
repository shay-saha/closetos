#!/usr/bin/env python3
import os
import secrets
from pathlib import Path

root = Path(__file__).resolve().parents[1]
environment = root / ".env"
if not environment.exists():
    values = {
        "DATABASE_PASSWORD": secrets.token_urlsafe(32),
        "NEXTAUTH_URL": "http://localhost:3000",
        "NEXTAUTH_SECRET": secrets.token_urlsafe(48),
        "OIDC_ISSUER": "http://localhost:8081/realms/closetos",
        "OIDC_CLIENT_ID": "closetos-web",
        "API_URL": "http://localhost:8080",
        "COGNITO_ISSUER_URI": "http://localhost:8081/realms/closetos",
        "COGNITO_CLIENT_ID": "closetos-web",
    }
    descriptor = os.open(environment, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "w") as target:
        target.write("".join(f"{key}={value}\n" for key, value in values.items()))

existing = {line.split("=", 1)[0] for line in environment.read_text().splitlines() if "=" in line}
local_media = {
    "MINIO_ROOT_USER": "closetos-local",
    "MINIO_ROOT_PASSWORD": secrets.token_urlsafe(32),
    "MEDIA_WORKER_TOKEN": secrets.token_urlsafe(48),
}
with environment.open("a") as target:
    for key, value in local_media.items():
        if key not in existing:
            target.write(f"{key}={value}\n")
os.chmod(environment, 0o600)

web_environment = root / "apps/web/.env.local"
if not web_environment.exists():
    lines = [line for line in environment.read_text().splitlines()
             if line.startswith(("NEXTAUTH_", "OIDC_", "API_URL"))]
    descriptor = os.open(web_environment, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "w") as target:
        target.write("\n".join(lines) + "\n")
