import base64
import hashlib
import json
import os
from typing import Protocol

import boto3
from botocore.config import Config
from botocore.exceptions import ClientError


def checksum(data: bytes) -> str:
    return base64.b64encode(hashlib.sha256(data).digest()).decode("ascii")


class StoragePort(Protocol):
    def read(self, key: str, limit: int) -> bytes: ...
    def write(self, key: str, data: bytes, mime_type: str) -> None: ...
    def optional_json(self, key: str) -> dict | None: ...


class S3Storage:
    def __init__(self):
        options = {}
        endpoint = os.getenv("S3_ENDPOINT")
        if endpoint:
            if os.getenv("STORAGE_MODE") != "local":
                raise ValueError("An endpoint override requires local storage mode")
            options = {
                "endpoint_url": endpoint,
                "aws_access_key_id": os.environ["MINIO_ROOT_USER"],
                "aws_secret_access_key": os.environ["MINIO_ROOT_PASSWORD"],
            }
        self.client = boto3.client(
            "s3",
            region_name=os.getenv("AWS_REGION", "eu-west-2"),
            config=Config(
                signature_version="s3v4",
                s3={"addressing_style": "path" if endpoint else "virtual"},
                connect_timeout=10,
                read_timeout=30,
                retries={"max_attempts": 3, "mode": "standard"},
            ),
            **options,
        )
        self.bucket = os.getenv("MEDIA_BUCKET", "closetos")

    def read(self, key: str, limit: int) -> bytes:
        response = self.client.get_object(Bucket=self.bucket, Key=key)
        with response["Body"] as stream:
            if response["ContentLength"] > limit:
                raise ValueError("Object exceeds its size limit")
            data = stream.read(limit + 1)
        if len(data) > limit:
            raise ValueError("Object exceeds its size limit")
        return data

    def write(self, key: str, data: bytes, mime_type: str) -> None:
        digest = checksum(data)
        self.client.put_object(
            Bucket=self.bucket, Key=key, Body=data, ContentType=mime_type, ChecksumSHA256=digest
        )
        response = self.client.head_object(Bucket=self.bucket, Key=key, ChecksumMode="ENABLED")
        if response.get("ChecksumSHA256") != digest or response["ContentLength"] != len(data):
            raise RuntimeError("Stored derivative failed verification")

    def optional_json(self, key: str) -> dict | None:
        try:
            return json.loads(self.read(key, 131_072))
        except ClientError as error:
            if error.response["Error"]["Code"] in {"NoSuchKey", "404"}:
                return None
            raise
