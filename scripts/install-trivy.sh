#!/usr/bin/env bash
set -euo pipefail

if [[ "$(uname -s)" != Linux || "$(uname -m)" != x86_64 ]]; then
  echo 'The pinned scanner installer requires Linux x86_64.' >&2
  exit 1
fi

installation_directory="${1:?Provide a scanner installation directory.}"
scanner_version='0.75.0'
archive_checksum='c6e65abddb348e25f10549df887045629cf28cc72453cd1c63acb717316b3f3f'
download_directory="$(mktemp -d)"
trap 'rm -rf "$download_directory"' EXIT

curl --fail --silent --show-error --location --retry 3 --max-time 180 \
  "https://github.com/aquasecurity/trivy/releases/download/v${scanner_version}/trivy_${scanner_version}_Linux-64bit.tar.gz" \
  --output "$download_directory/scanner.tar.gz"
echo "$archive_checksum  $download_directory/scanner.tar.gz" | sha256sum --check --status
tar --extract --gzip --file "$download_directory/scanner.tar.gz" --directory "$download_directory" trivy
mkdir -p "$installation_directory"
install -m 0755 "$download_directory/trivy" "$installation_directory/trivy"
"$installation_directory/trivy" --version
