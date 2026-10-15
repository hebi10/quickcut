#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -ne 1 ]; then
  echo "Usage: bash scripts/verify-release-apk.sh signed.apk" >&2
  exit 2
fi
repo_dir=$(cd -- "$(dirname -- "$0")/.." && pwd)
apksigner_path=${APKSIGNER:-${ANDROID_HOME:?Set ANDROID_HOME or APKSIGNER}/build-tools/36.0.0/apksigner}
report=$("$apksigner_path" verify --verbose --print-certs "$1")
actual=$(printf '%s\n' "$report" | sed -n 's/^Signer #[0-9][0-9]* certificate SHA-256 digest: //p')
expected=$(tr -d '\r\n' < "$repo_dir/config/release-certificate.sha256")
if [ "$actual" != "$expected" ]; then
  echo "Release signing certificate differs from the pinned QuickCut key." >&2
  exit 1
fi
printf '%s\n' "$report"
echo "QuickCut persistent release certificate verified"
