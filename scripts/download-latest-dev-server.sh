#!/usr/bin/env bash
set -euo pipefail

output=${1:?Usage: download-latest-dev-server.sh <output-file>}
metadata_url=https://repo.maven.apache.org/maven2/io/fluxzero/tools/fluxzero-dev-server/maven-metadata.xml
metadata=$(mktemp)
trap 'rm -f "$metadata"' EXIT

curl -fsSL "$metadata_url" -o "$metadata"
version=$(python3 - "$metadata" <<'PY'
import re
import sys
import xml.etree.ElementTree as ET

versions = []
for value in (node.text or "" for node in ET.parse(sys.argv[1]).findall("./versioning/versions/version")):
    match = re.fullmatch(r"1\.(\d+)\.(\d+)", value.strip())
    if match:
        versions.append((int(match.group(1)), int(match.group(2)), value.strip()))
if not versions:
    raise SystemExit("Maven Central metadata contains no stable 1.x Fluxzero dev server")
print(max(versions)[2])
PY
)

mkdir -p "$(dirname "$output")"
artifact_url="https://repo.maven.apache.org/maven2/io/fluxzero/tools/fluxzero-dev-server/$version/fluxzero-dev-server-$version-standalone.jar"
curl -fsSL "$artifact_url" -o "$output"
printf 'Downloaded Fluxzero dev server %s to %s\n' "$version" "$output"
