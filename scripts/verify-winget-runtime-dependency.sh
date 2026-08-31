#!/usr/bin/env bash

set -euo pipefail

manifest="${1:?Usage: verify-winget-runtime-dependency.sh <installer-manifest>}"

if [[ ! -f "$manifest" ]]; then
  echo "WinGet installer manifest not found: $manifest" >&2
  exit 1
fi

if ! awk '
  { sub(/\r$/, "") }
  $0 == "Dependencies:" { in_dependencies = 1; next }
  in_dependencies && /^[^[:space:]-]/ { in_dependencies = 0 }
  in_dependencies && /^[[:space:]]*- PackageIdentifier: Microsoft\.VCRedist\.2015\+\.x64$/ { found = 1 }
  END { exit found ? 0 : 1 }
' "$manifest"; then
  echo "WinGet manifest must declare Microsoft.VCRedist.2015+.x64 under Dependencies.PackageDependencies." >&2
  exit 1
fi

echo "WinGet Visual C++ runtime dependency verified."
