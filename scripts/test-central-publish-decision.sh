#!/usr/bin/env bash
set -euo pipefail

script="$(cd "$(dirname "$0")" && pwd)/central-publish-decision.sh"

assert_decision() {
  local expected="$1"
  local existing_count="$2"
  local version_reserved="$3"
  local run_attempt="$4"
  local actual
  actual="$($script "$existing_count" "$version_reserved" "$run_attempt")"
  if [[ "$actual" != "$expected" ]]; then
    printf 'Expected %s for existing=%s reserved=%s attempt=%s, got %s\n' \
      "$expected" "$existing_count" "$version_reserved" "$run_attempt" "$actual" >&2
    exit 1
  fi
}

assert_decision publish 0 true 1
assert_decision conflict 1 true 1
assert_decision conflict 2 true 1
assert_decision complete 3 true 1
assert_decision wait 0 false 1
assert_decision wait 1 false 1
assert_decision wait 2 false 1
assert_decision complete 3 false 1
assert_decision wait 0 true 2
assert_decision wait 1 true 2
assert_decision wait 2 true 2
assert_decision complete 3 true 2
