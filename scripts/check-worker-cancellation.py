import argparse
import base64
import hashlib
import json
import os
import secrets
import subprocess
import time
from urllib.error import HTTPError, URLError
from urllib.request import ProxyHandler, Request, build_opener
from uuid import uuid4


def docker(*arguments, timeout=30):
    result = subprocess.run(
        ["docker", *arguments],
        check=True,
        capture_output=True,
        text=True,
        timeout=timeout,
    )
    return (
        result.stdout + result.stderr if arguments[0] == "logs" else result.stdout
    ).strip()


def response(endpoint, path, token=None, payload=None, method="POST"):
    headers = {}
    if token is not None:
        headers["Authorization"] = "Bearer " + token
    if payload is not None:
        headers["Content-Type"] = "application/json"
    request = Request(
        endpoint + path,
        data=json.dumps(payload).encode() if payload is not None else None,
        headers=headers,
        method=method,
    )
    try:
        with build_opener(ProxyHandler({})).open(request, timeout=3) as result:
            return result.status, json.loads(result.read(65536))
    except HTTPError as error:
        return error.code, json.loads(error.read(65536))


def start(image, name, volume, environment):
    command = [
        "docker",
        "run",
        "--detach",
        "--name",
        name,
        "--read-only",
        "--publish",
        "127.0.0.1::8800",
        "--cpus",
        "2",
        "--memory",
        "4g",
        "--volume",
        volume + ":/state",
        "--entrypoint",
        "python",
    ]
    for key in environment:
        command.extend(["-e", key])
    subprocess.run(
        [
            *command,
            image,
            "-m",
            "uvicorn",
            "closetos_media.server:app",
            "--host",
            "0.0.0.0",
            "--port",
            "8800",
        ],
        env={**os.environ, **environment},
        check=True,
        capture_output=True,
        text=True,
        timeout=30,
    )
    ports = json.loads(
        docker("inspect", "--format", "{{json .NetworkSettings.Ports}}", name)
    )
    endpoint = "http://127.0.0.1:" + ports["8800/tcp"][0]["HostPort"]
    deadline = time.monotonic() + 60
    while time.monotonic() < deadline:
        if docker("inspect", "--format", "{{.State.Running}}", name) != "true":
            raise RuntimeError("The packaged worker did not start")
        try:
            if response(endpoint, "/health", method="GET")[0] == 200:
                return endpoint
        except (URLError, TimeoutError, ConnectionError):
            pass
        time.sleep(0.2)
    raise TimeoutError("The packaged worker did not become healthy")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--image", default="closetos-media:local-runtime")
    args = parser.parse_args()
    volume = "closetos-worker-cancellation-" + uuid4().hex
    names = [volume + suffix for suffix in ("-first", "-second", "-replacement")]
    token = secrets.token_urlsafe(48)
    environment = {
        "MEDIA_WORKER_TOKEN": token,
        "MEDIA_WORKER_STATE_DIR": "/state",
        "STORAGE_MODE": "local",
        "S3_ENDPOINT": "http://127.0.0.1:9000",
        "MINIO_ROOT_USER": "unused-test-user",
        "MINIO_ROOT_PASSWORD": secrets.token_urlsafe(32),
        "EMBEDDING_PROVIDER": "local",
        "AWS_EC2_METADATA_DISABLED": "true",
        "AWS_REGION": "eu-west-2",
        "BEDROCK_ANALYSIS_MODEL_ID": "",
    }
    owner, garment, image, job, wardrobe = (uuid4() for _ in range(5))
    prefix = f"users/{owner}/garments/{garment}/images/{image}/"
    payload = {
        "jobId": str(job),
        "imageId": str(image),
        "garmentId": str(garment),
        "wardrobeId": str(wardrobe),
        "sourceKey": prefix + "original.png",
        "outputPrefix": prefix + "pipelines/1-r1/",
        "mimeType": "image/png",
        "expectedSize": 1,
        "checksumSha256": base64.b64encode(hashlib.sha256(b"x").digest()).decode(),
        "pipelineVersion": "1-r1",
        "requestId": str(uuid4()),
    }
    docker("volume", "create", volume)
    try:
        docker(
            "run",
            "--rm",
            "--user",
            "0",
            "--volume",
            volume + ":/state",
            "--entrypoint",
            "sh",
            args.image,
            "-c",
            "chown 10001:10001 /state && chmod 700 /state",
        )
        endpoint = start(args.image, names[0], volume, environment)
        assert response(endpoint, f"/owners/{owner}/cancel")[0] == 401
        assert response(endpoint, f"/owners/{owner}/cancel", token) == (
            200,
            {"drained": True},
        )
        assert response(endpoint, "/process", token, payload) == (
            410,
            {"detail": "Processing was cancelled"},
        )
        # The second process must not serve a registry that omits the first process's active jobs.
        try:
            start(args.image, names[1], volume, environment)
        except RuntimeError:
            assert docker("wait", names[1], timeout=60) != "0"
            assert "Another media worker is using this state directory" in docker(
                "logs", names[1]
            )
        else:
            raise AssertionError("An independent worker acquired the same registry")
        docker("stop", "--time", "10", names[0])
        endpoint = start(args.image, names[2], volume, environment)
        assert response(endpoint, "/process", token, payload) == (
            410,
            {"detail": "Processing was cancelled"},
        )
        assert response(endpoint, f"/owners/{owner}/cancel", token) == (
            200,
            {"drained": True},
        )
        print(
            "Worker cancellation container passed: authenticated cancellation, durable restart rejection, exclusive registry; no storage or AWS requests"
        )
    finally:
        for name in names:
            subprocess.run(
                ["docker", "rm", "--force", "--volumes", name],
                check=False,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                timeout=30,
            )
        subprocess.run(
            ["docker", "volume", "rm", volume],
            check=False,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            timeout=30,
        )


if __name__ == "__main__":
    main()
