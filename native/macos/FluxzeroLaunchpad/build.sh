#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
BUILD_DIR="$SCRIPT_DIR/build"
APP_NAME="Fluxzero Launchpad"
APP_BUNDLE="$BUILD_DIR/$APP_NAME.app"
CONTENTS_DIR="$APP_BUNDLE/Contents"
MACOS_DIR="$CONTENTS_DIR/MacOS"
RESOURCES_DIR="$CONTENTS_DIR/Resources"
INFO_PLIST="$SCRIPT_DIR/AppBundle/Info.plist"
ICON_SOURCE="$REPO_ROOT/desktop/src/main/resources/icons/fluxzero.icns"

swift build --package-path "$SCRIPT_DIR" -c release
BIN_DIR="$(swift build --package-path "$SCRIPT_DIR" -c release --show-bin-path)"
BINARY_SOURCE="$BIN_DIR/FluxzeroLaunchpad"

if [[ ! -x "$BINARY_SOURCE" ]]; then
    echo "Missing Swift build output: $BINARY_SOURCE" >&2
    exit 1
fi

if [[ ! -f "$ICON_SOURCE" ]]; then
    echo "Missing app icon: $ICON_SOURCE" >&2
    exit 1
fi

rm -rf "$APP_BUNDLE"
mkdir -p "$MACOS_DIR" "$RESOURCES_DIR"
cp "$BINARY_SOURCE" "$MACOS_DIR/FluxzeroLaunchpad"
cp "$INFO_PLIST" "$CONTENTS_DIR/Info.plist"
cp "$ICON_SOURCE" "$RESOURCES_DIR/Fluxzero.icns"
chmod 755 "$MACOS_DIR/FluxzeroLaunchpad"

if command -v codesign >/dev/null 2>&1; then
    codesign --force --sign - "$APP_BUNDLE" >/dev/null
fi

echo "$APP_BUNDLE"
