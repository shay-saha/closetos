#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
set -a
source .env
set +a
export STORAGE_MODE=local S3_ENDPOINT=http://localhost:9000 MEDIA_BUCKET=closetos
export OMP_NUM_THREADS=2
export EMBEDDING_PROVIDER=local
exec uv run --project workers/media-processor uvicorn closetos_media.server:app --host 127.0.0.1 --port 8800
