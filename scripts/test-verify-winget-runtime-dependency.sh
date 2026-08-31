#!/usr/bin/env bash

set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
test_dir="$(mktemp -d)"
trap 'rm -rf "$test_dir"' EXIT

cat > "$test_dir/valid.yaml" <<'EOF'
PackageIdentifier: Fluxzero.FluxzeroCLI
PackageVersion: 1.17.1
InstallerType: zip
Dependencies:
  PackageDependencies:
  - PackageIdentifier: Microsoft.VCRedist.2015+.x64
Installers:
- Architecture: x64
ManifestType: installer
EOF

"$script_dir/verify-winget-runtime-dependency.sh" "$test_dir/valid.yaml" >/dev/null
cp "$test_dir/valid.yaml" "$test_dir/valid-before.yaml"
"$script_dir/ensure-winget-runtime-dependency.sh" "$test_dir/valid.yaml"
cmp "$test_dir/valid-before.yaml" "$test_dir/valid.yaml"

cat > "$test_dir/missing.yaml" <<'EOF'
PackageIdentifier: Fluxzero.FluxzeroCLI
PackageVersion: 1.17.1
InstallerType: zip
Installers:
- Architecture: x64
ManifestType: installer
EOF

if "$script_dir/verify-winget-runtime-dependency.sh" "$test_dir/missing.yaml" >/dev/null 2>&1; then
  echo "Expected a manifest without the Visual C++ runtime dependency to be rejected." >&2
  exit 1
fi
"$script_dir/ensure-winget-runtime-dependency.sh" "$test_dir/missing.yaml" >/dev/null
"$script_dir/verify-winget-runtime-dependency.sh" "$test_dir/missing.yaml" >/dev/null
[[ "$(grep -c '^Dependencies:$' "$test_dir/missing.yaml")" == "1" ]]

cat > "$test_dir/misplaced.yaml" <<'EOF'
PackageIdentifier: Fluxzero.FluxzeroCLI
PackageVersion: 1.17.1
InstallerType: zip
Installers:
- Architecture: x64
  PackageIdentifier: Microsoft.VCRedist.2015+.x64
ManifestType: installer
EOF

if "$script_dir/verify-winget-runtime-dependency.sh" "$test_dir/misplaced.yaml" >/dev/null 2>&1; then
  echo "Expected a misplaced Visual C++ runtime package identifier to be rejected." >&2
  exit 1
fi

printf 'PackageIdentifier: Fluxzero.FluxzeroCLI\r\nDependencies:\r\n  PackageDependencies:\r\n  - PackageIdentifier: Microsoft.VCRedist.2015+.x64\r\nInstallers:\r\n' > "$test_dir/crlf.yaml"
"$script_dir/verify-winget-runtime-dependency.sh" "$test_dir/crlf.yaml" >/dev/null

cat > "$test_dir/malformed.yaml" <<'EOF'
PackageIdentifier: Fluxzero.FluxzeroCLI
PackageVersion: 1.17.1
ManifestType: installer
EOF

if "$script_dir/ensure-winget-runtime-dependency.sh" "$test_dir/malformed.yaml" >/dev/null 2>&1; then
  echo "Expected a manifest without an Installers section to be rejected." >&2
  exit 1
fi

echo "WinGet runtime dependency tests passed."
