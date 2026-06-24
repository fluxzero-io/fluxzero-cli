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
ICON_SOURCE="$REPO_ROOT/native/assets/icons/fluxzero.icns"
ASSET_DIR="$SCRIPT_DIR/Assets"
EXECUTABLE_NAME="FluxzeroLaunchpad"
CONFIGURATION="${CONFIGURATION:-release}"
MACOS_DEPLOYMENT_TARGET="${MACOSX_DEPLOYMENT_TARGET:-14.0}"
BUILD_UNIVERSAL="${BUILD_UNIVERSAL:-1}"
APP_VERSION="${APP_VERSION:-${VERSION:-}}"
APP_BUILD="${APP_BUILD:-}"
CLI_BUNDLE_DIR="$RESOURCES_DIR/FluxzeroCLI"
FLUXZERO_CLI_DIR="${FLUXZERO_CLI_DIR:-}"
FLUXZERO_CLI_UNIVERSAL="${FLUXZERO_CLI_UNIVERSAL:-}"
FLUXZERO_CLI_ARM64="${FLUXZERO_CLI_ARM64:-}"
FLUXZERO_CLI_AMD64="${FLUXZERO_CLI_AMD64:-${FLUXZERO_CLI_X86_64:-}}"
REQUIRE_BUNDLED_CLI="${REQUIRE_BUNDLED_CLI:-0}"
CODE_SIGN_IDENTITY="${CODE_SIGN_IDENTITY:--}"
CODE_SIGN_ENTITLEMENTS="${CODE_SIGN_ENTITLEMENTS:-}"

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

find_cli_in_dir() {
    local arch="$1"
    local dir="$2"
    local legacy_arch="$arch"
    if [[ "$arch" == "amd64" ]]; then
        legacy_arch="x86_64"
    fi

    if [[ -z "$dir" ]]; then
        return 1
    fi

    local candidates=(
        "$dir/fz-$arch"
        "$dir/flux-macos-$arch"
        "$dir/fz-$legacy_arch"
        "$dir/flux-macos-$legacy_arch"
    )
    local candidate
    for candidate in "${candidates[@]}"; do
        if [[ -f "$candidate" ]]; then
            printf '%s\n' "$candidate"
            return 0
        fi
    done
    return 1
}

copy_cli_binary() {
    local source="$1"
    local target_name="$2"
    if [[ -z "$source" ]]; then
        return 1
    fi
    if [[ ! -f "$source" ]]; then
        echo "Missing Fluxzero CLI binary: $source" >&2
        exit 1
    fi
    cp "$source" "$CLI_BUNDLE_DIR/$target_name"
    chmod 755 "$CLI_BUNDLE_DIR/$target_name"
    return 0
}

sign_target() {
    local target="$1"
    if ! command -v codesign >/dev/null 2>&1; then
        return 0
    fi

    local args=(--force --sign "$CODE_SIGN_IDENTITY" --options runtime)
    if [[ -n "$CODE_SIGN_ENTITLEMENTS" ]]; then
        args+=(--entitlements "$CODE_SIGN_ENTITLEMENTS")
    fi
    if [[ "$CODE_SIGN_IDENTITY" != "-" ]]; then
        args+=(--timestamp)
    fi
    codesign "${args[@]}" "$target" >/dev/null
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
mkdir -p "$MACOS_DIR" "$RESOURCES_DIR" "$CLI_BUNDLE_DIR"
if [[ "$BUILD_UNIVERSAL" == "1" ]]; then
    lipo -create "$ARM64_BINARY" "$X86_64_BINARY" -output "$MACOS_DIR/$EXECUTABLE_NAME"
else
    cp "$BINARY_SOURCE" "$MACOS_DIR/$EXECUTABLE_NAME"
fi
cp "$INFO_PLIST" "$CONTENTS_DIR/Info.plist"
if [[ -n "$APP_VERSION" ]]; then
    /usr/libexec/PlistBuddy -c "Set :CFBundleShortVersionString $APP_VERSION" "$CONTENTS_DIR/Info.plist"
fi
if [[ -n "$APP_BUILD" ]]; then
    /usr/libexec/PlistBuddy -c "Set :CFBundleVersion $APP_BUILD" "$CONTENTS_DIR/Info.plist"
fi
cp "$ICON_SOURCE" "$RESOURCES_DIR/Fluxzero.icns"
cp "$ASSET_DIR/FluxzeroMenuBar.svg" "$RESOURCES_DIR/FluxzeroMenuBar.svg"
cp "$ASSET_DIR/FluxzeroMenuBarTemplate.png" "$RESOURCES_DIR/FluxzeroMenuBarTemplate.png"
cp "$ASSET_DIR/CursorCube.svg" "$RESOURCES_DIR/CursorCube.svg"
cp "$ASSET_DIR/ClaudeCodeMark.svg" "$RESOURCES_DIR/ClaudeCodeMark.svg"
cp "$ASSET_DIR/CodexIcon.svg" "$RESOURCES_DIR/CodexIcon.svg"
cp "$ASSET_DIR/CodexIcon.png" "$RESOURCES_DIR/CodexIcon.png"
chmod 755 "$MACOS_DIR/$EXECUTABLE_NAME"

COPIED_ARM64_CLI=0
COPIED_AMD64_CLI=0
COPIED_UNIVERSAL_CLI=0

if copy_cli_binary "$FLUXZERO_CLI_UNIVERSAL" "fz-universal"; then
    COPIED_UNIVERSAL_CLI=1
fi
if copy_cli_binary "${FLUXZERO_CLI_ARM64:-$(find_cli_in_dir arm64 "$FLUXZERO_CLI_DIR" || true)}" "fz-arm64"; then
    COPIED_ARM64_CLI=1
fi
if copy_cli_binary "${FLUXZERO_CLI_AMD64:-$(find_cli_in_dir amd64 "$FLUXZERO_CLI_DIR" || true)}" "fz-amd64"; then
    COPIED_AMD64_CLI=1
fi

if [[ "$REQUIRE_BUNDLED_CLI" == "1" && "$COPIED_UNIVERSAL_CLI" != "1" ]]; then
    if [[ "$BUILD_UNIVERSAL" == "1" && ("$COPIED_ARM64_CLI" != "1" || "$COPIED_AMD64_CLI" != "1") ]]; then
        echo "REQUIRE_BUNDLED_CLI=1 requires FLUXZERO_CLI_UNIVERSAL or both FLUXZERO_CLI_ARM64 and FLUXZERO_CLI_AMD64." >&2
        exit 1
    fi
    if [[ "$BUILD_UNIVERSAL" != "1" && "$COPIED_ARM64_CLI" != "1" && "$COPIED_AMD64_CLI" != "1" ]]; then
        echo "REQUIRE_BUNDLED_CLI=1 requires a bundled Fluxzero CLI binary." >&2
        exit 1
    fi
fi

if [[ "$COPIED_UNIVERSAL_CLI" != "1" && "$COPIED_ARM64_CLI" != "1" && "$COPIED_AMD64_CLI" != "1" ]]; then
    rmdir "$CLI_BUNDLE_DIR"
else
    for cli_binary in "$CLI_BUNDLE_DIR"/*; do
        [[ -f "$cli_binary" ]] || continue
        sign_target "$cli_binary"
    done
fi

sign_target "$APP_BUNDLE"

echo "$APP_BUNDLE"
