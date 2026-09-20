from pathlib import Path

import boto3
from botocore.config import Config
from botocore.exceptions import ClientError

root = Path(__file__).resolve().parents[1]
values = dict(line.split("=", 1) for line in (root / ".env").read_text().splitlines() if "=" in line)
storage = boto3.client(
    "s3", endpoint_url="http://localhost:9000", region_name="eu-west-2",
    aws_access_key_id=values["MINIO_ROOT_USER"],
    aws_secret_access_key=values["MINIO_ROOT_PASSWORD"],
    config=Config(signature_version="s3v4", s3={"addressing_style": "path"}),
)
try:
    storage.head_bucket(Bucket="closetos")
except ClientError as error:
    if error.response["ResponseMetadata"]["HTTPStatusCode"] != 404:
        raise
    storage.create_bucket(Bucket="closetos", CreateBucketConfiguration={"LocationConstraint": "eu-west-2"})
