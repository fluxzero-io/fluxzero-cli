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
ASSET_DIR="$SCRIPT_DIR/Assets"
EXECUTABLE_NAME="FluxzeroLaunchpad"
CONFIGURATION="${CONFIGURATION:-release}"
MACOS_DEPLOYMENT_TARGET="${MACOSX_DEPLOYMENT_TARGET:-15.0}"
BUILD_UNIVERSAL="${BUILD_UNIVERSAL:-1}"

mkdir -p "$BUILD_DIR"

build_host_binary() {
    swift build --package-path "$SCRIPT_DIR" -c "$CONFIGURATION" >&2
    local bin_dir
    bin_dir="$(swift build --package-path "$SCRIPT_DIR" -c "$CONFIGURATION" --show-bin-path)"
    local binary="$bin_dir/$EXECUTABLE_NAME"
    if [[ ! -x "$binary" ]]; then
        echo "Missing Swift build output: $binary" >&2
        exit 1
    fi
    printf '%s\n' "$binary"
}

build_slice() {
    local arch="$1"
    local triple="$arch-apple-macosx$MACOS_DEPLOYMENT_TARGET"
    local scratch_path="$BUILD_DIR/.build-$arch"

    swift build --package-path "$SCRIPT_DIR" -c "$CONFIGURATION" --triple "$triple" --scratch-path "$scratch_path" >&2
    local bin_dir
    bin_dir="$(swift build --package-path "$SCRIPT_DIR" -c "$CONFIGURATION" --triple "$triple" --scratch-path "$scratch_path" --show-bin-path)"
    local binary="$bin_dir/$EXECUTABLE_NAME"
    if [[ ! -x "$binary" ]]; then
        echo "Missing Swift build output for $arch: $binary" >&2
        exit 1
    fi
    printf '%s\n' "$binary"
}

if [[ ! -f "$ICON_SOURCE" ]]; then
    echo "Missing app icon: $ICON_SOURCE" >&2
    exit 1
fi

if [[ "$BUILD_UNIVERSAL" == "1" ]]; then
    if ! command -v lipo >/dev/null 2>&1; then
        echo "lipo is required for BUILD_UNIVERSAL=1" >&2
        exit 1
    fi

    ARM64_BINARY="$(build_slice arm64)"
    X86_64_BINARY="$(build_slice x86_64)"
else
    BINARY_SOURCE="$(build_host_binary)"
fi

rm -rf "$APP_BUNDLE"
mkdir -p "$MACOS_DIR" "$RESOURCES_DIR"
if [[ "$BUILD_UNIVERSAL" == "1" ]]; then
    lipo -create "$ARM64_BINARY" "$X86_64_BINARY" -output "$MACOS_DIR/$EXECUTABLE_NAME"
else
    cp "$BINARY_SOURCE" "$MACOS_DIR/$EXECUTABLE_NAME"
fi
cp "$INFO_PLIST" "$CONTENTS_DIR/Info.plist"
cp "$ICON_SOURCE" "$RESOURCES_DIR/Fluxzero.icns"
cp "$ASSET_DIR/FluxzeroMenuBar.svg" "$RESOURCES_DIR/FluxzeroMenuBar.svg"
cp "$ASSET_DIR/FluxzeroMenuBarTemplate.png" "$RESOURCES_DIR/FluxzeroMenuBarTemplate.png"
chmod 755 "$MACOS_DIR/$EXECUTABLE_NAME"

if command -v codesign >/dev/null 2>&1; then
    codesign --force --sign - "$APP_BUNDLE" >/dev/null
fi

echo "$APP_BUNDLE"
