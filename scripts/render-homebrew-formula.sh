#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 5 ]]; then
  echo "Usage: $0 <version> <macos-arm64-sha256> <macos-amd64-sha256> <linux-amd64-sha256> <output>" >&2
  exit 2
fi

version="$1"
macos_arm64_sha256="$2"
macos_amd64_sha256="$3"
linux_amd64_sha256="$4"
output="$5"

if [[ ! "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "Release version must be stable SemVer, got: $version" >&2
  exit 1
fi

for checksum in "$macos_arm64_sha256" "$macos_amd64_sha256" "$linux_amd64_sha256"; do
  if [[ ! "$checksum" =~ ^[0-9a-f]{64}$ ]]; then
    echo "Invalid SHA-256 checksum: $checksum" >&2
    exit 1
  fi
done

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
template="$script_dir/../packaging/homebrew/fluxzero.rb.template"
mkdir -p "$(dirname "$output")"

sed \
  -e "s/@VERSION@/$version/g" \
  -e "s/@MACOS_ARM64_SHA256@/$macos_arm64_sha256/g" \
  -e "s/@MACOS_AMD64_SHA256@/$macos_amd64_sha256/g" \
  -e "s/@LINUX_AMD64_SHA256@/$linux_amd64_sha256/g" \
  "$template" > "$output"

if grep -qE '@[A-Z0-9_]+@' "$output"; then
  echo "Formula still contains unresolved placeholders" >&2
  exit 1
fi
