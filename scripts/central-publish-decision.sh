#!/usr/bin/env bash
set -euo pipefail

existing_count="${1:?existing artifact count is required}"
version_reserved="${2:?version reservation state is required}"

# A version reserved by this job cannot have been submitted by an earlier job,
# even when the overall workflow is a rerun. A pre-existing reservation may
# already have an accepted Central deployment, so only wait in that case.
if [[ "$existing_count" == "3" ]]; then
  printf 'complete\n'
elif [[ "$version_reserved" == "true" ]]; then
  if [[ "$existing_count" == "0" ]]; then
    printf 'publish\n'
  else
    printf 'conflict\n'
  fi
else
  printf 'wait\n'
fi
