#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ -f .env ]]; then
  set -a
  source .env
  set +a
fi
export OMP_NUM_THREADS=2
case "${1:-all}" in
  all|embeddings) ;;
  *) echo 'Usage: prepare-local-models.sh [all|embeddings]' >&2; exit 2 ;;
esac
uv run --project workers/media-processor python -m closetos_media download-embeddings
if [[ "${1:-all}" == all ]]; then
  uv run --project workers/media-processor python -m closetos_media download-model
fi
