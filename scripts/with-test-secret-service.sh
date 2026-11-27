#!/usr/bin/env bash
# Disposable synthetic keyring inside an already isolated test D-Bus session.
set -euo pipefail
export HOME=$(mktemp -d)
export XDG_RUNTIME_DIR=$(mktemp -d)
trap 'rm -rf "$HOME" "$XDG_RUNTIME_DIR"' EXIT
mkdir -p "$HOME/.local/share/keyrings"
printf 'synthetic-ci-keyring\n' | gnome-keyring-daemon --unlock --components=secrets
"$@"
