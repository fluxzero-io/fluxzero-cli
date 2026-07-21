#!/usr/bin/env bash
set -euo pipefail

script="$(cd "$(dirname "$0")" && pwd)/determine-release-version.sh"
repository="$(mktemp -d)"
trap 'rm -rf "$repository"' EXIT
git -C "$repository" init -q
git -C "$repository" config user.name test
git -C "$repository" config user.email test@example.com

commit() {
  printf '%s\n' "$1" >> "$repository/history"
  git -C "$repository" add history
  git -C "$repository" commit -q -m "$1"
}

assert_version() {
  local expected="$1"
  shift
  local actual
  actual="$(cd "$repository" && "$script" "$@" | sed -n 's/^version=//p')"
  if [[ "$actual" != "$expected" ]]; then
    printf 'Expected version %s, got %s\n' "$expected" "$actual" >&2
    exit 1
  fi
}

commit "fix(cli): establish baseline"
git -C "$repository" tag 2.0.0-RC1
git -C "$repository" tag 1.3.36
commit "feat(dev): add development environment"
assert_version 1.4.0 push 1
git -C "$repository" tag 1.4.0
assert_version 1.4.0 push 1
assert_version 1.4.1 repository_dispatch 1
assert_version 1.4.1 repository_dispatch 2
git -C "$repository" tag -a 1.4.1 -m $'Fluxzero CLI 1.4.1\n\nRelease run: 42'
assert_version 1.4.1 repository_dispatch 42

commit "fix(dev): improve startup"
assert_version 1.4.2 push 1
git -C "$repository" tag -a 1.4.2 -m $'Fluxzero CLI 1.4.2\n\nRelease run: 51'
commit "fix(dev): recover after reserved release"
assert_version 1.4.3 push 52
commit "feat(dev)!: replace launcher protocol"
assert_version 2.0.0 push 1
