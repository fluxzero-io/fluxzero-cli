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
EXAMPLES_RELEASE_TAG=${EXAMPLES_RELEASE_TAG:-"latest"}
GITHUB_TOKEN=${GITHUB_TOKEN:-}
CACHE_DIR=${CACHE_DIR:-"./build/examples-snapshot"}
OUTPUT_DIR=${OUTPUT_DIR:-"./build/generated/resources/templates"}
REFRESH_EXAMPLES=${REFRESH_EXAMPLES:-"false"}

# Extract owner/repo from the repo URL
extract_owner_repo() {
  local repo_url=$1
  if [[ $repo_url == https://github.com/* ]]; then
    local path=${repo_url#https://github.com/}
    path=${path%.git}
    echo "$path"
  elif [[ $repo_url == git@github.com:* ]]; then
    local path=${repo_url#git@github.com:}
    path=${path%.git}
    echo "$path"
  else
    echo ""
  fi
}

# Fetch the templates.zip download URL from GitHub Releases API
fetch_release_asset_url() {
  local repo_url=$1 tag=$2
  local owner_repo
  owner_repo=$(extract_owner_repo "$repo_url")
  [[ -n "$owner_repo" ]] || { echo "ERROR: Could not extract owner/repo from: $repo_url" >&2; return 1; }

  local api_url
  if [[ "$tag" == "latest" ]]; then
    api_url="https://api.github.com/repos/${owner_repo}/releases/latest"
  else
    api_url="https://api.github.com/repos/${owner_repo}/releases/tags/${tag}"
  fi

  echo "Querying GitHub Releases API: $api_url" >&2

  local curl_args=(-fsSL -H "Accept: application/vnd.github+json")
  if [[ -n "$GITHUB_TOKEN" ]]; then
    curl_args+=(-H "Authorization: Bearer $GITHUB_TOKEN")
  fi

  local response
  if ! response=$(curl "${curl_args[@]}" "$api_url" 2>&1); then
    echo "ERROR: Failed to query GitHub Releases API at $api_url" >&2
    if [[ -z "$GITHUB_TOKEN" ]]; then
      echo "HINT: Set GITHUB_TOKEN to avoid rate limits (unauthenticated: 60 req/hr, authenticated: 5000 req/hr)" >&2
    fi
    return 1
  fi

  # Parse browser_download_url for templates.zip from JSON using grep/sed (no jq dependency)
  local download_url
  download_url=$(echo "$response" | grep -o '"browser_download_url"[[:space:]]*:[[:space:]]*"[^"]*templates\.zip"' | sed 's/.*"browser_download_url"[[:space:]]*:[[:space:]]*"\([^"]*\)"/\1/')

  if [[ -z "$download_url" ]]; then
    echo "ERROR: No templates.zip asset found in release" >&2
    echo "Available assets:" >&2
    echo "$response" | grep -o '"browser_download_url"[[:space:]]*:[[:space:]]*"[^"]*"' | sed 's/"browser_download_url"[[:space:]]*:[[:space:]]*//; s/"//g' >&2
    return 1
  fi

  echo "$download_url"
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

  if [[ -n "$EXAMPLES_ZIP_URL" ]]; then
    ZIP_URL="$EXAMPLES_ZIP_URL"
  else
    ZIP_URL=$(fetch_release_asset_url "$EXAMPLES_REPO_URL" "$EXAMPLES_RELEASE_TAG")
  fi
  [[ -n "$ZIP_URL" ]] || { echo "ERROR: Could not determine examples ZIP URL" >&2; exit 1; }

  echo "Downloading examples ZIP from: $ZIP_URL"
  tmpzip=$(mktemp -t examples.XXXXXX.zip)
  trap 'rm -f "$tmpzip"' EXIT

  # Use token for download if available (release assets may need auth)
  download_args=(-fsSL)
  if [[ -n "$GITHUB_TOKEN" ]]; then
    download_args+=(-H "Authorization: Bearer $GITHUB_TOKEN")
  fi
  curl "${download_args[@]}" "$ZIP_URL" -o "$tmpzip"

  echo "Unpacking examples..."
  unzip -q "$tmpzip" -d "$CACHE_DIR"

  # Flatten if a single top-level directory exists (safety net for source archives)
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

true > "$INDEX_FILE"
while IFS= read -r name; do
  [[ -z "$name" ]] && continue
  echo "Zipping template: $name"
  (cd "$CACHE_DIR/$name" && zip -qr "$OUTPUT_DIR/$name.zip" .)
  echo "$name" >> "$INDEX_FILE"
done < "$tmpnames"
rm -f "$tmpnames"

echo "Prepared templates in $OUTPUT_DIR"
