#!/usr/bin/env bash

set -euo pipefail

manifest="${1:?Usage: ensure-winget-runtime-dependency.sh <installer-manifest>}"
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [[ ! -f "$manifest" ]]; then
  echo "WinGet installer manifest not found: $manifest" >&2
  exit 1
fi

if "$script_dir/verify-winget-runtime-dependency.sh" "$manifest" >/dev/null 2>&1; then
  exit 0
fi

temporary_manifest="$(mktemp "${manifest}.tmp.XXXXXX")"
trap 'rm -f "$temporary_manifest"' EXIT

if ! awk '
  BEGIN { inserted = 0 }
  {
    line = $0
    sub(/\r$/, "", line)
    if (!inserted && line == "Installers:") {
      print "Dependencies:"
      print "  PackageDependencies:"
      print "  - PackageIdentifier: Microsoft.VCRedist.2015+.x64"
      inserted = 1
    }
    print line
  }
  END { exit inserted ? 0 : 1 }
' "$manifest" > "$temporary_manifest"; then
  echo "Could not locate the root Installers section in WinGet manifest: $manifest" >&2
  exit 1
fi

mv "$temporary_manifest" "$manifest"
trap - EXIT
"$script_dir/verify-winget-runtime-dependency.sh" "$manifest" >/dev/null

echo "Added the required Visual C++ runtime dependency to the WinGet manifest."
