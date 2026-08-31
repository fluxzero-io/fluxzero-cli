#!/usr/bin/env bash

set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
test_dir="$(mktemp -d)"
fake_bin="$test_dir/bin"
sync_state="$test_dir/sync-state"
mkdir -p "$fake_bin"
trap 'rm -rf "$test_dir"' EXIT

printf '%s\n' '#!/usr/bin/env bash' > "$fake_bin/gh"
printf '%s\n' 'set -euo pipefail' >> "$fake_bin/gh"
printf '%s\n' 'request="$*"' >> "$fake_bin/gh"
printf '%s\n' 'case "$request" in' >> "$fake_bin/gh"
printf '%s\n' '  "api user --jq .login") echo "fluxzero-publisher" ;;' >> "$fake_bin/gh"
printf '%s\n' '  "api repos/fluxzero-publisher/winget-pkgs --jq [.parent.full_name, .default_branch, .permissions.push] | @tsv")' >> "$fake_bin/gh"
printf '%s\n' '    if [[ "${SCENARIO:?}" == "read-only" ]]; then' >> "$fake_bin/gh"
printf '%s\n' '      printf "microsoft/winget-pkgs\\tmaster\\tfalse\\n"' >> "$fake_bin/gh"
printf '%s\n' '    else' >> "$fake_bin/gh"
printf '%s\n' '      printf "microsoft/winget-pkgs\\tmaster\\ttrue\\n"' >> "$fake_bin/gh"
printf '%s\n' '    fi' >> "$fake_bin/gh"
printf '%s\n' '    ;;' >> "$fake_bin/gh"
printf '%s\n' '  "api repos/microsoft/winget-pkgs --jq .default_branch") echo "master" ;;' >> "$fake_bin/gh"
printf '%s\n' '  "api repos/microsoft/winget-pkgs/compare/master...fluxzero-publisher:master --jq [.status, .ahead_by, .behind_by] | @tsv")' >> "$fake_bin/gh"
printf '%s\n' '    if [[ "${SCENARIO:?}" == "identical" || -e "${GH_FAKE_SYNC_STATE:?}" ]]; then' >> "$fake_bin/gh"
printf '%s\n' '      printf "identical\\t0\\t0\\n"' >> "$fake_bin/gh"
printf '%s\n' '    elif [[ "$SCENARIO" == "ahead" ]]; then' >> "$fake_bin/gh"
printf '%s\n' '      printf "ahead\\t2\\t0\\n"' >> "$fake_bin/gh"
printf '%s\n' '    else' >> "$fake_bin/gh"
printf '%s\n' '      printf "behind\\t0\\t7\\n"' >> "$fake_bin/gh"
printf '%s\n' '    fi' >> "$fake_bin/gh"
printf '%s\n' '    ;;' >> "$fake_bin/gh"
printf '%s\n' '  "api --method POST repos/fluxzero-publisher/winget-pkgs/merge-upstream -f branch=master")' >> "$fake_bin/gh"
printf '%s\n' '    if [[ "${SCENARIO:?}" == "sync-failure" ]]; then' >> "$fake_bin/gh"
printf '%s\n' '      echo "HTTP 422: workflow scope required" >&2' >> "$fake_bin/gh"
printf '%s\n' '      exit 1' >> "$fake_bin/gh"
printf '%s\n' '    fi' >> "$fake_bin/gh"
printf '%s\n' '    : > "${GH_FAKE_SYNC_STATE:?}"' >> "$fake_bin/gh"
printf '%s\n' '    echo "{}"' >> "$fake_bin/gh"
printf '%s\n' '    ;;' >> "$fake_bin/gh"
printf '%s\n' '  *) echo "Unexpected gh invocation: $request" >&2; exit 2 ;;' >> "$fake_bin/gh"
printf '%s\n' 'esac' >> "$fake_bin/gh"
chmod +x "$fake_bin/gh"

run_sync() {
  SCENARIO="$1" GH_FAKE_SYNC_STATE="$sync_state" PATH="$fake_bin:$PATH" \
    "$script_dir/sync-winget-fork.sh"
}

output="$(run_sync identical)"
[[ "$output" == *"already synchronized"* ]]
[[ ! -e "$sync_state" ]]

output="$(run_sync behind)"
[[ "$output" == *"7 commit(s) behind"* ]]
[[ "$output" == *"publisher fork synchronized"* ]]
[[ -e "$sync_state" ]]

rm -f "$sync_state"
if output="$(run_sync ahead 2>&1)"; then
  echo "Expected a fork with unique commits to be rejected." >&2
  exit 1
fi
[[ "$output" == *"refusing to overwrite"* ]]
[[ ! -e "$sync_state" ]]

if output="$(run_sync read-only 2>&1)"; then
  echo "Expected a token without write access to the publisher fork to be rejected." >&2
  exit 1
fi
[[ "$output" == *"must grant Contents read/write access"* ]]

if output="$(run_sync sync-failure 2>&1)"; then
  echo "Expected a failed upstream sync to be reported." >&2
  exit 1
fi
[[ "$output" == *"HTTP 422"* ]]
[[ "$output" == *"fine-grained PAT"* ]]
[[ "$output" == *"Contents and Workflows read/write access"* ]]
[[ "$output" == *"upstream changed files under .github/workflows"* ]]

echo "WinGet fork synchronization tests passed."
