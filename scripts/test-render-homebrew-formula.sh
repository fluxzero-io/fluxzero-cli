#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
test_dir="$(mktemp -d)"
trap 'rm -rf "$test_dir"' EXIT

arm_sha="$(printf 'a%.0s' {1..64})"
intel_sha="$(printf 'b%.0s' {1..64})"
linux_sha="$(printf 'c%.0s' {1..64})"
output="$test_dir/fluxzero.rb"

"$script_dir/render-homebrew-formula.sh" 1.2.3 "$arm_sha" "$intel_sha" "$linux_sha" "$output"
ruby -c "$output"
if grep -Eq '^[[:space:]]*version ' "$output"; then
  echo "Formula should infer its version from release URLs" >&2
  exit 1
fi
grep -Fq 'releases/download/1.2.3/flux-macos-arm64' "$output"
grep -Fq 'releases/download/1.2.3/flux-macos-amd64' "$output"
grep -Fq 'releases/download/1.2.3/flux-linux-amd64' "$output"
grep -Fq "sha256 \"$arm_sha\"" "$output"
grep -Fq "sha256 \"$intel_sha\"" "$output"
grep -Fq "sha256 \"$linux_sha\"" "$output"

if "$script_dir/render-homebrew-formula.sh" 1.2.3-SNAPSHOT "$arm_sha" "$intel_sha" "$linux_sha" "$output" \
  >/dev/null 2>&1; then
  echo "Prerelease version was unexpectedly accepted" >&2
  exit 1
fi
