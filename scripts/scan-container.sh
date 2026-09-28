#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
trivy_binary="${TRIVY:-$repository_root/.security/bin/trivy}"
report_directory="${SECURITY_REPORT_DIR:-$repository_root/.security/reports}"
service="${1:?Choose api, web, or media-worker.}"
image_reference="${2:?Provide the exact local image ID or immutable registry digest.}"
case "$service" in
  api|web|media-worker) ;;
  *) echo 'Choose api, web, or media-worker.' >&2; exit 1 ;;
esac

mkdir -p "$report_directory"
"$trivy_binary" --config /dev/null image "$image_reference" --scanners vuln --pkg-types os,library \
  --severity CRITICAL --exit-code 1 --format json --output "$report_directory/$service.json" \
  --ignorefile /dev/null --ignore-unfixed=false --timeout 15m --no-progress
