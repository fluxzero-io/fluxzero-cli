#!/usr/bin/env bash
set -euo pipefail

# Exercise the actual Linux release binary with an x86-64 CPU lacking AVX/AVX2.
# A normal run on the build runner would not catch an accidental x86-64-v3 baseline.
cli="${1:?Usage: test-native-cpu-compatibility.sh /path/to/linux-amd64-cli}"
test -x "$cli"
command -v qemu-x86_64 >/dev/null

timeout 60s qemu-x86_64 -cpu Nehalem "$cli" version
timeout 60s qemu-x86_64 -cpu Nehalem "$cli" templates list
