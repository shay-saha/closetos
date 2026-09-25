#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
terraform_binary="${TERRAFORM:-terraform}"

for module in bootstrap aws; do
  module_directory="$repository_root/infra/$module"
  "$terraform_binary" -chdir="$module_directory" fmt -check -recursive
  "$terraform_binary" -chdir="$module_directory" init -backend=false -input=false -lockfile=readonly
  "$terraform_binary" -chdir="$module_directory" validate -no-color
  "$terraform_binary" -chdir="$module_directory" test -no-color
done
