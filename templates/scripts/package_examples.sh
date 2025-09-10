#!/usr/bin/env bash
# Ensure we run under bash even if invoked via `sh script.sh`
if [ -z "${BASH_VERSION:-}" ]; then exec /usr/bin/env bash "$0" "$@"; fi
set -euo pipefail

# Optional debug mode: set DEBUG_TEMPLATES=true to enable tracing
if [[ ${DEBUG_TEMPLATES:-} == "true" ]]; then
  set -x
fi

# Helpful error context
trap 'code=$?; echo "ERROR: Script failed at line $LINENO (cmd: $BASH_COMMAND) with exit $code" >&2; exit $code' ERR

# Inputs (env vars); Gradle passes these when invoking this script
EXAMPLES_ZIP_URL=${EXAMPLES_ZIP_URL:-}
EXAMPLES_REPO_URL=${EXAMPLES_REPO_URL:-"https://github.com/fluxzero-io/fluxzero-examples.git"}
EXAMPLES_BRANCH=${EXAMPLES_BRANCH:-"main"}
CACHE_DIR=${CACHE_DIR:-"./build/examples-snapshot"}
OUTPUT_DIR=${OUTPUT_DIR:-"./build/generated/resources/templates"}
REFRESH_EXAMPLES=${REFRESH_EXAMPLES:-"false"}

derive_zip_url() {
  local repo_url=$1 branch=$2
  if [[ $repo_url == https://github.com/* ]]; then
    local path=${repo_url#https://github.com/}
    path=${path%.git}
    echo "https://github.com/${path}/archive/refs/heads/${branch}.zip"
  elif [[ $repo_url == git@github.com:* ]]; then
    local path=${repo_url#git@github.com:}
    path=${path%.git}
    echo "https://github.com/${path}/archive/refs/heads/${branch}.zip"
  else
    echo ""  # unknown
  fi
}

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || { echo "ERROR: '$1' is required" >&2; exit 1; }
}

require_cmd unzip
require_cmd zip
require_cmd curl

# Skip download if cache exists and refresh not requested
if [[ -d "$CACHE_DIR" && -n "$(ls -A "$CACHE_DIR" 2>/dev/null || true)" && "$REFRESH_EXAMPLES" != "true" ]]; then
  echo "Using cached examples at $CACHE_DIR (set REFRESH_EXAMPLES=true to refresh)"
else
  rm -rf "$CACHE_DIR"
  mkdir -p "$CACHE_DIR"

  ZIP_URL=${EXAMPLES_ZIP_URL:-$(derive_zip_url "$EXAMPLES_REPO_URL" "$EXAMPLES_BRANCH")}
  [[ -n "$ZIP_URL" ]] || { echo "ERROR: Could not derive examples ZIP URL" >&2; exit 1; }

  echo "Downloading examples ZIP from: $ZIP_URL"
  tmpzip=$(mktemp -t examples.XXXXXX.zip)
  trap 'rm -f "$tmpzip"' EXIT
  curl -fsSL "$ZIP_URL" -o "$tmpzip"

  echo "Unpacking examples..."
  unzip -q "$tmpzip" -d "$CACHE_DIR"

  # Flatten if a single top-level directory exists
  shopt -s nullglob
  entries=("$CACHE_DIR"/*)
  if [[ ${#entries[@]} -eq 1 && -d "${entries[0]}" ]]; then
    top="${entries[0]}"
    shopt -s dotglob
    mv "$top"/* "$CACHE_DIR"/ || true
    shopt -u dotglob
    rmdir "$top" || true
  fi
fi

# Package each template directory
rm -rf "$OUTPUT_DIR"
mkdir -p "$OUTPUT_DIR"
# Normalize to absolute path so zips write correctly when cwd changes
OUTPUT_DIR="$(cd "$OUTPUT_DIR" && pwd)"
if [[ ! -w "$OUTPUT_DIR" ]]; then
  echo "ERROR: Output directory not writable: $OUTPUT_DIR" >&2
  exit 1
fi
INDEX_FILE="$OUTPUT_DIR/templates.csv"

# Gather top-level directories and sort (robust with set -e)
shopt -s nullglob
names=()
for d in "$CACHE_DIR"/*/; do
  [[ -d "$d" ]] || continue
  names+=("$(basename "$d")")
done

if [[ ${#names[@]} -eq 0 ]]; then
  echo "ERROR: No templates found in $CACHE_DIR" >&2
  exit 1
fi

# Sort names without relying on Bash 4+ features
tmpnames=$(mktemp -t templates.XXXX)
printf '%s\n' "${names[@]}" | sort > "$tmpnames"

> "$INDEX_FILE"
while IFS= read -r name; do
  [[ -z "$name" ]] && continue
  echo "Zipping template: $name"
  (cd "$CACHE_DIR/$name" && zip -qr "$OUTPUT_DIR/$name.zip" .)
  echo "$name" >> "$INDEX_FILE"
done < "$tmpnames"
rm -f "$tmpnames"

echo "Prepared templates in $OUTPUT_DIR"
