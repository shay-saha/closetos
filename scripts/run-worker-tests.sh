#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ -f .env ]]; then
  set -a
  source .env
  set +a
fi
export EMBEDDING_EVALUATION=1
./scripts/prepare-local-models.sh embeddings
uv run --project workers/media-processor ruff check workers/media-processor
uv run --project workers/media-processor ruff format --check workers/media-processor
exec uv run --project workers/media-processor pytest workers/media-processor/tests
