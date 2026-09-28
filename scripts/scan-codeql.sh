#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
codeql_binary="${CODEQL:-codeql}"
report_directory="${SECURITY_REPORT_DIR:-$repository_root/.security/reports}/codeql"
database_directory="$(mktemp -d "${TMPDIR:-/tmp}/closetos-codeql-XXXXXX")"
mkdir -p "$report_directory"
cd "$repository_root"

for language in java javascript python actions; do
  arguments=(database create "$database_directory/$language" --language "$language" \
    --source-root "$repository_root" --threads 2 --ram 4096)
  if [[ "$language" == java ]]; then
    arguments+=(--command '/bin/bash -c "cd apps/api && ./mvnw -B -ntp clean package -DskipTests -Dspotless.skip=true"')
  else
    arguments+=(--build-mode none)
  fi
  "$codeql_binary" "${arguments[@]}"
  "$codeql_binary" database analyze "$database_directory/$language" \
    "codeql/$language-queries:codeql-suites/$language-security-extended.qls" \
    --format sarifv2.1.0 --output "$report_directory/$language.sarif" \
    --sarif-category "/language:$language" --threads 2 --ram 4096
  node scripts/check-codeql-results.mjs "$report_directory/$language.sarif"
done

rm -rf "$database_directory"
