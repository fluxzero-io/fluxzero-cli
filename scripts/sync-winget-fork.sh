#!/usr/bin/env bash

set -euo pipefail

upstream_repository="${WINGET_UPSTREAM_REPOSITORY:-microsoft/winget-pkgs}"
fork_repository_name="${WINGET_FORK_REPOSITORY_NAME:-winget-pkgs}"

fail() {
  echo "::error title=WinGet fork synchronization::$*" >&2
  exit 1
}

command -v gh >/dev/null 2>&1 || fail "The GitHub CLI is required."

publisher="$(gh api user --jq .login)"
[[ -n "$publisher" ]] || fail "Could not determine the WinGet publisher account from GH_TOKEN."

fork_repository="$publisher/$fork_repository_name"
fork_metadata="$(gh api "repos/$fork_repository" --jq '[.parent.full_name, .default_branch, .permissions.push] | @tsv')"
IFS=$'\t' read -r fork_parent fork_branch can_push <<< "$fork_metadata"
[[ "$fork_parent" == "$upstream_repository" ]] || fail \
  "$fork_repository must be a fork of $upstream_repository, but its parent is ${fork_parent:-unknown}."
[[ -n "$fork_branch" ]] || fail "Could not determine the default branch of $fork_repository."
[[ "$can_push" == "true" ]] || fail \
  "WINGET_FORK_SYNC_GITHUB_TOKEN must grant Contents read/write access to $fork_repository."

upstream_branch="$(gh api "repos/$upstream_repository" --jq .default_branch)"
[[ -n "$upstream_branch" ]] || fail "Could not determine the default branch of $upstream_repository."

read_fork_state() {
  gh api \
    "repos/$upstream_repository/compare/$upstream_branch...$publisher:$fork_branch" \
    --jq '[.status, .ahead_by, .behind_by] | @tsv'
}

validate_count() {
  local name="$1"
  local value="$2"
  [[ "$value" =~ ^[0-9]+$ ]] || fail "GitHub returned an invalid $name count: $value."
}

fork_state="$(read_fork_state)"
IFS=$'\t' read -r status ahead_by behind_by <<< "$fork_state"
validate_count "ahead" "$ahead_by"
validate_count "behind" "$behind_by"

if (( ahead_by > 0 )); then
  fail "$fork_repository/$fork_branch contains $ahead_by commit(s) that are not in $upstream_repository/$upstream_branch; refusing to overwrite them."
fi

if (( behind_by == 0 )); then
  [[ "$status" == "identical" ]] || fail \
    "GitHub returned inconsistent fork state (status=$status, ahead=$ahead_by, behind=$behind_by)."
  echo "WinGet publisher fork is already synchronized: $fork_repository/$fork_branch."
  exit 0
fi

echo "Synchronizing $fork_repository/$fork_branch with $upstream_repository/$upstream_branch ($behind_by commit(s) behind)."
if ! sync_output="$(gh api \
  --method POST \
  "repos/$fork_repository/merge-upstream" \
  -f "branch=$fork_branch" 2>&1)"; then
  echo "$sync_output" >&2
  fail "GitHub could not fast-forward the WinGet publisher fork. Ensure WINGET_FORK_SYNC_GITHUB_TOKEN is a fine-grained PAT limited to $fork_repository with Contents read/write access."
fi

for retry_delay in 0 1 2 3 4; do
  (( retry_delay == 0 )) || sleep "$retry_delay"
  fork_state="$(read_fork_state)"
  IFS=$'\t' read -r status ahead_by behind_by <<< "$fork_state"
  validate_count "ahead" "$ahead_by"
  validate_count "behind" "$behind_by"
  [[ "$status" == "identical" && "$ahead_by" == "0" && "$behind_by" == "0" ]] && break
done
[[ "$status" == "identical" && "$ahead_by" == "0" && "$behind_by" == "0" ]] || fail \
  "$fork_repository/$fork_branch is still not identical to $upstream_repository/$upstream_branch after synchronization (status=$status, ahead=$ahead_by, behind=$behind_by)."

echo "WinGet publisher fork synchronized: $fork_repository/$fork_branch."
