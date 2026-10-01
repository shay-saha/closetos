import argparse
import io
import os
import subprocess
from pathlib import Path
from uuid import uuid4

import boto3
from botocore.config import Config
from closetos_media.models import ProcessingResult, WorkflowJob
from closetos_media.storage import checksum
from PIL import Image


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--image", default="closetos-media:local-runtime")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    values = dict(
        line.split("=", 1)
        for line in (root / ".env").read_text().splitlines()
        if "=" in line
    )
    credentials = {
        name: values[name] for name in ("MINIO_ROOT_USER", "MINIO_ROOT_PASSWORD")
    }
    storage = boto3.client(
        "s3",
        endpoint_url="http://localhost:9000",
        region_name="eu-west-2",
        aws_access_key_id=credentials["MINIO_ROOT_USER"],
        aws_secret_access_key=credentials["MINIO_ROOT_PASSWORD"],
        config=Config(signature_version="s3v4", s3={"addressing_style": "path"}),
    )
    owner_id, garment_id, image_id = (uuid4() for _ in range(3))
    prefix = f"users/{owner_id}/garments/{garment_id}/images/{image_id}/"
    source = (root / "e2e/fixtures/shirt.png").read_bytes()
    job = WorkflowJob(
        job_id=uuid4(),
        image_id=image_id,
        garment_id=garment_id,
        wardrobe_id=uuid4(),
        source_key=prefix + "original.png",
        output_prefix=prefix + "pipelines/1-r1/",
        mime_type="image/png",
        expected_size=len(source),
        checksum_sha256=checksum(source),
        pipeline_version="1-r1",
        request_id=str(uuid4()),
    )
    environment = {
        **os.environ,
        **credentials,
        "AWS_REGION": "eu-west-2",
        "STORAGE_MODE": "local",
        "S3_ENDPOINT": "http://localhost:9000",
        "MEDIA_BUCKET": "closetos",
        "WORKFLOW_INPUT": job.model_dump_json(by_alias=True),
    }
    container_name = f"closetos-worker-check-{uuid4()}"
    command = [
        "docker",
        "run",
        "--rm",
        "--name",
        container_name,
        "--read-only",
        "--network",
        "host",
        "--cpus",
        "2",
        "--memory",
        "8g",
        "--memory-swap",
        "8g",
    ]
    for name in (
        *credentials,
        "AWS_REGION",
        "STORAGE_MODE",
        "S3_ENDPOINT",
        "MEDIA_BUCKET",
        "WORKFLOW_INPUT",
    ):
        command.extend(["-e", name])
    command.append(args.image)
    try:
        storage.put_object(
            Bucket="closetos",
            Key=job.source_key,
            Body=source,
            ContentType=job.mime_type,
            ChecksumSHA256=job.checksum_sha256,
        )
        for stage in ("transform", "enrich"):
            subprocess.run([*command, stage], env=environment, check=True, timeout=1000)
        manifest_key = job.output_prefix + "manifest.json"
        manifest = storage.get_object(Bucket="closetos", Key=manifest_key)[
            "Body"
        ].read()
        result = ProcessingResult.model_validate_json(manifest)
        assert result.job_id == job.job_id and result.image_id == job.image_id
        assert result.source_checksum_sha256 == job.checksum_sha256
        assert result.segmentation_model == "birefnet-general-lite"
        assert (
            result.analysis_key is None
            and result.analysis_failure == "ANALYSIS_NOT_CONFIGURED"
        )
        assert set(result.assets) == {
            "isolated",
            "display",
            "card",
            "thumbnail",
            "mask",
        }
        for role, asset in result.assets.items():
            data = storage.get_object(Bucket="closetos", Key=asset.key)["Body"].read()
            assert len(data) == asset.size and checksum(data) == asset.checksum_sha256
            with Image.open(io.BytesIO(data)) as image:
                assert image.size == (asset.width, asset.height)
                assert max(image.size) <= {
                    "display": 1600,
                    "card": 768,
                    "thumbnail": 256,
                }.get(role, 4096)
                alpha = image if role == "mask" else image.getchannel("A")
                assert alpha.getextrema() == (0, 255)
        stored = {
            item["Key"]: (item["ETag"], item["LastModified"])
            for item in storage.list_objects_v2(Bucket="closetos", Prefix=prefix)[
                "Contents"
            ]
        }
        for stage in ("transform", "enrich"):
            subprocess.run([*command, stage], env=environment, check=True, timeout=60)
        replayed = {
            item["Key"]: (item["ETag"], item["LastModified"])
            for item in storage.list_objects_v2(Bucket="closetos", Prefix=prefix)[
                "Contents"
            ]
        }
        assert stored == replayed, "Replayed stages rewrote verified outputs"
        print(
            f"Worker container passed: native segmentation, 5 verified assets, stage replay; foreground {result.foreground_fraction:.3f}"
        )
    finally:
        subprocess.run(
            ["docker", "rm", "--force", container_name],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            check=False,
        )
        paginator = storage.get_paginator("list_object_versions")
        for page in paginator.paginate(Bucket="closetos", Prefix=prefix):
            objects = [
                {"Key": item["Key"], "VersionId": item["VersionId"]}
                for item in [*page.get("Versions", []), *page.get("DeleteMarkers", [])]
            ]
            if objects:
                storage.delete_objects(Bucket="closetos", Delete={"Objects": objects})


if __name__ == "__main__":
    main()
