#!/usr/bin/env bash
set -euo pipefail

existing_count="${1:?existing artifact count is required}"
version_reserved="${2:?version reservation state is required}"
run_attempt="${3:?workflow run attempt is required}"

# A reserved version may only be uploaded by its first workflow attempt. Reruns
# wait for the accepted Central deployment instead of creating a duplicate.
if [[ "$existing_count" == "3" ]]; then
  printf 'complete\n'
elif [[ "$version_reserved" == "false" || "$run_attempt" != "1" ]]; then
  printf 'wait\n'
elif [[ "$existing_count" == "0" ]]; then
  printf 'publish\n'
else
  printf 'conflict\n'
fi
