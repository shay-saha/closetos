#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
trivy_binary="${TRIVY:-$repository_root/.security/bin/trivy}"
report_directory="${SECURITY_REPORT_DIR:-$repository_root/.security/reports}"
mkdir -p "$report_directory"

"$trivy_binary" --config /dev/null fs "$repository_root" --scanners vuln --pkg-types library --include-dev-deps \
  --severity CRITICAL --exit-code 1 --format json --output "$report_directory/dependencies.json" \
  --ignorefile /dev/null --ignore-unfixed=false --timeout 10m --no-progress \
  --skip-dirs "$repository_root/.security" --skip-dirs "$repository_root/.git" \
  --skip-dirs "$repository_root/apps/api/target" \
  --skip-dirs "$repository_root/apps/web/.next" \
  --skip-dirs "$repository_root/apps/web/node_modules" \
  --skip-dirs "$repository_root/node_modules" \
  --skip-dirs "$repository_root/workers/media-processor/.venv"
