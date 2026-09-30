#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
terraform_binary="${TERRAFORM:-terraform}"
test_output="$(mktemp)"
trap 'rm -f "$test_output"' EXIT

node --test "$repository_root/scripts/check-infrastructure-tests.test.mjs" "$repository_root/scripts/check-iam-permissions.test.mjs"

for module in bootstrap aws; do
  module_directory="$repository_root/infra/$module"
  "$terraform_binary" -chdir="$module_directory" fmt -check -recursive
  "$terraform_binary" -chdir="$module_directory" init -backend=false -input=false -lockfile=readonly
  "$terraform_binary" -chdir="$module_directory" validate -no-color
  test_status=0
  "$terraform_binary" -chdir="$module_directory" test -verbose -json > "$test_output" || test_status=$?
  node "$repository_root/scripts/check-infrastructure-tests.mjs" "$test_output" "$module"
  if (( test_status != 0 )); then
    exit "$test_status"
  fi
done
