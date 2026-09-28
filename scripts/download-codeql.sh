#!/usr/bin/env bash
set -euo pipefail

bundle_path="${1:?Provide a CodeQL bundle archive path.}"
bundle_version='2.27.1'
bundle_checksum='1d380f79896ededc654c7b21fafb3360136f1aeb678ad4df4df9af3910c6b815'
download_directory="$(mktemp -d)"
trap 'rm -rf "$download_directory"' EXIT

curl --fail --silent --show-error --location --retry 3 --max-time 300 \
  "https://github.com/github/codeql-action/releases/download/codeql-bundle-v${bundle_version}/codeql-bundle-linux64.tar.gz" \
  --output "$download_directory/bundle.tar.gz"
echo "$bundle_checksum  $download_directory/bundle.tar.gz" | sha256sum --check --status
mkdir -p "$(dirname "$bundle_path")"
install -m 0644 "$download_directory/bundle.tar.gz" "$bundle_path"
