#!/usr/bin/env bash
set -euo pipefail

event_name="${1:-push}"
run_id="${2:-}"

stable_tags() {
  git tag "$@" --sort=-v:refname | grep -E '^[0-9]+\.[0-9]+\.[0-9]+$' || true
}

latest_tag="$(stable_tags | head -n 1)"
existing_tag="$(stable_tags --points-at HEAD | head -n 1)"

if [[ -n "$run_id" ]]; then
  while IFS= read -r tag; do
    if git for-each-ref --format='%(contents)' "refs/tags/$tag" | grep -Fq "Release run: $run_id"; then
      printf 'version=%s\nalready_tagged=true\n' "$tag"
      exit 0
    fi
  done < <(stable_tags --points-at HEAD)
fi

if [[ -n "$existing_tag" && "$event_name" == "push" ]]; then
  printf 'version=%s\nalready_tagged=true\n' "$existing_tag"
  exit 0
fi

if [[ -z "$latest_tag" ]]; then
  printf 'version=1.0.0\nalready_tagged=false\n'
  exit 0
fi

IFS=. read -r major minor patch <<< "$latest_tag"
range="${latest_tag}..HEAD"
messages="$(git log --format='%s%n%b' "$range")"
subjects="$(git log --format='%s' "$range")"
if printf '%s\n' "$messages" | grep -Eq '(^|[[:space:]])BREAKING[ -]CHANGE:|^[a-z]+(\([^)]*\))?!:'; then
  version="$((major + 1)).0.0"
elif printf '%s\n' "$subjects" | grep -Eq '^feat(\([^)]*\))?:'; then
  version="${major}.$((minor + 1)).0"
else
  version="${major}.${minor}.$((patch + 1))"
fi
printf 'version=%s\nalready_tagged=false\n' "$version"
