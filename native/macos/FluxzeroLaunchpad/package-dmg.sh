#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUILD_DIR="$SCRIPT_DIR/build"
APP_NAME="Fluxzero Launchpad"
APP_BUNDLE="$BUILD_DIR/$APP_NAME.app"
VOLUME_NAME="${VOLUME_NAME:-Fluxzero Launchpad}"
DMG_NAME="${DMG_NAME:-Fluxzero-Launchpad.dmg}"
DMG_PATH="$BUILD_DIR/$DMG_NAME"
DMG_SIZE="${DMG_SIZE:-256m}"
ICON_SIZE=128
CODE_SIGN_IDENTITY="${CODE_SIGN_IDENTITY:-}"

copy_jpackage_background() {
    local output_path="$1"
    local java_home="${JAVA_HOME:-}"
    local resource_dir="$TMP_DIR/jpackage-resources"
    local resource_path="classes/jdk/jpackage/internal/resources/background_dmg.tiff"

    if [[ -z "$java_home" ]]; then
        java_home="$(/usr/libexec/java_home 2>/dev/null || true)"
    fi

    if [[ -z "$java_home" || ! -f "$java_home/jmods/jdk.jpackage.jmod" ]]; then
        echo "jpackage background resource not found; creating DMG without a Finder background image." >&2
        return 1
    fi

    mkdir -p "$resource_dir"
    if ! (cd "$resource_dir" && jar xf "$java_home/jmods/jdk.jpackage.jmod" "$resource_path"); then
        echo "Could not extract jpackage background resource; creating DMG without a Finder background image." >&2
        return 1
    fi
    if [[ ! -f "$resource_dir/$resource_path" ]]; then
        echo "jpackage background resource is missing from the JDK; creating DMG without a Finder background image." >&2
        return 1
    fi
    cp "$resource_dir/$resource_path" "$output_path"
}

layout_dmg() {
    local volume_name="$1"
    local mount_dir="$2"
    local background_path="${3:-}"

    osascript <<OSA
tell application "Finder"
    tell disk "$volume_name"
        open
        set current view of container window to icon view
        set toolbar visible of container window to false
        set statusbar visible of container window to false
        set the bounds of container window to {400, 100, 920, 440}
        set viewOptions to the icon view options of container window
        set arrangement of viewOptions to not arranged
        set icon size of viewOptions to $ICON_SIZE
        if "$background_path" is not "" then
            set background picture of viewOptions to POSIX file "$background_path"
        end if
        set position of item "$APP_NAME.app" of container window to {120, 130}
        set position of item "Applications" of container window to {390, 130}
        update without registering applications
        delay 5
        close
    end tell
end tell
OSA
}

detach_existing_volume() {
    local mount_point
    while IFS= read -r mount_point; do
        local volume_basename
        volume_basename="$(basename "$mount_point")"
        if [[ "$volume_basename" == "$VOLUME_NAME" || "$volume_basename" == "$VOLUME_NAME "* ]]; then
            hdiutil detach "$mount_point" >/dev/null 2>&1 || true
        fi
    done < <(find /Volumes -maxdepth 1 -mindepth 1 -type d -print 2>/dev/null)
}

"$SCRIPT_DIR/build.sh"

if [[ ! -d "$APP_BUNDLE" ]]; then
    echo "Missing app bundle: $APP_BUNDLE" >&2
    exit 1
fi

if ! command -v hdiutil >/dev/null 2>&1; then
    echo "hdiutil is required to create the DMG" >&2
    exit 1
fi

if ! command -v osascript >/dev/null 2>&1; then
    echo "osascript is required to style the DMG Finder window" >&2
    exit 1
fi

TMP_DIR="$(mktemp -d /private/tmp/fluxzero-launchpad-dmg.XXXXXX)"
RW_DMG="$TMP_DIR/$APP_NAME-rw.dmg"
MOUNT_DIR=""
DEVICE=""

cleanup() {
    if [[ -n "$DEVICE" ]]; then
        hdiutil detach "$DEVICE" >/dev/null 2>&1 || true
    fi
    rm -rf "$TMP_DIR"
}
trap cleanup EXIT

rm -f "$DMG_PATH"
detach_existing_volume

hdiutil create \
    -size "$DMG_SIZE" \
    -fs HFS+ \
    -nospotlight \
    -volname "$VOLUME_NAME" \
    -ov \
    "$RW_DMG" >/dev/null

ATTACH_OUTPUT="$(hdiutil attach "$RW_DMG" -readwrite -noverify -noautoopen)"
DEVICE="$(printf '%s\n' "$ATTACH_OUTPUT" | awk '/Apple_HFS|Apple_APFS/ { print $1; exit }')"
MOUNT_DIR="$(diskutil info "$DEVICE" | awk -F': *' '/Mount Point/ { print $2; exit }')"

if [[ -z "$DEVICE" || -z "$MOUNT_DIR" ]]; then
    echo "Failed to mount staging DMG" >&2
    exit 1
fi

ditto "$APP_BUNDLE" "$MOUNT_DIR/$APP_NAME.app"
ln -s /Applications "$MOUNT_DIR/Applications"
mkdir -p "$MOUNT_DIR/.background"
BACKGROUND_PATH="$MOUNT_DIR/.background/background_dmg.tiff"
if copy_jpackage_background "$BACKGROUND_PATH"; then
    layout_dmg "$VOLUME_NAME" "$MOUNT_DIR" "$BACKGROUND_PATH"
else
    rmdir "$MOUNT_DIR/.background" 2>/dev/null || true
    layout_dmg "$VOLUME_NAME" "$MOUNT_DIR"
fi

if [[ -d "$MOUNT_DIR/.background" ]] && command -v SetFile >/dev/null 2>&1; then
    SetFile -a V "$MOUNT_DIR/.background"
fi

rm -rf "$MOUNT_DIR/.fseventsd" "$MOUNT_DIR/.Trashes"
sync
hdiutil detach "$DEVICE" >/dev/null
DEVICE=""

hdiutil convert "$RW_DMG" \
    -format UDZO \
    -imagekey zlib-level=9 \
    -o "$DMG_PATH" >/dev/null

hdiutil verify "$DMG_PATH" >/dev/null

if [[ -n "$CODE_SIGN_IDENTITY" && "$CODE_SIGN_IDENTITY" != "-" ]]; then
    codesign --force --sign "$CODE_SIGN_IDENTITY" --timestamp "$DMG_PATH" >/dev/null
fi

echo "$DMG_PATH"
