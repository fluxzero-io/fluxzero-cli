#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DMG_PATH="${1:-$SCRIPT_DIR/build/Fluxzero-Launchpad.dmg}"
NOTARY_KEYCHAIN_PROFILE="${NOTARY_KEYCHAIN_PROFILE:-}"
NOTARY_KEY_ID="${NOTARY_KEY_ID:-${APPLE_NOTARY_KEY_ID:-}}"
NOTARY_ISSUER_ID="${NOTARY_ISSUER_ID:-${APPLE_NOTARY_ISSUER_ID:-}}"
NOTARY_KEY_PATH="${NOTARY_KEY_PATH:-}"
NOTARY_KEY_BASE64="${NOTARY_KEY_BASE64:-${APPLE_NOTARY_KEY_BASE64:-}}"
TMP_DIR=""

if [[ ! -f "$DMG_PATH" ]]; then
    echo "Missing DMG: $DMG_PATH" >&2
    exit 1
fi

if ! command -v xcrun >/dev/null 2>&1; then
    echo "xcrun is required for notarization." >&2
    exit 1
fi

cleanup() {
    if [[ -n "$TMP_DIR" ]]; then
        rm -rf "$TMP_DIR"
    fi
}
trap cleanup EXIT

if [[ -n "$NOTARY_KEYCHAIN_PROFILE" ]]; then
    xcrun notarytool submit "$DMG_PATH" \
        --keychain-profile "$NOTARY_KEYCHAIN_PROFILE" \
        --wait
elif [[ -n "$NOTARY_KEY_ID" || -n "$NOTARY_ISSUER_ID" || -n "$NOTARY_KEY_PATH" || -n "$NOTARY_KEY_BASE64" ]]; then
    : "${NOTARY_KEY_ID:?Set NOTARY_KEY_ID, APPLE_NOTARY_KEY_ID, or NOTARY_KEYCHAIN_PROFILE.}"
    : "${NOTARY_ISSUER_ID:?Set NOTARY_ISSUER_ID, APPLE_NOTARY_ISSUER_ID, or NOTARY_KEYCHAIN_PROFILE.}"
    if [[ -z "$NOTARY_KEY_PATH" ]]; then
        : "${NOTARY_KEY_BASE64:?Set NOTARY_KEY_PATH, NOTARY_KEY_BASE64, APPLE_NOTARY_KEY_BASE64, or NOTARY_KEYCHAIN_PROFILE.}"
        TMP_DIR="$(mktemp -d /private/tmp/fluxzero-launchpad-notary.XXXXXX)"
        NOTARY_KEY_PATH="$TMP_DIR/AuthKey_$NOTARY_KEY_ID.p8"
        printf '%s' "$NOTARY_KEY_BASE64" | base64 -D > "$NOTARY_KEY_PATH"
        chmod 600 "$NOTARY_KEY_PATH"
    fi
    xcrun notarytool submit "$DMG_PATH" \
        --key "$NOTARY_KEY_PATH" \
        --key-id "$NOTARY_KEY_ID" \
        --issuer "$NOTARY_ISSUER_ID" \
        --wait
else
    : "${APPLE_ID:?Set APPLE_ID or NOTARY_KEYCHAIN_PROFILE.}"
    : "${APPLE_TEAM_ID:?Set APPLE_TEAM_ID or NOTARY_KEYCHAIN_PROFILE.}"
    : "${APPLE_APP_SPECIFIC_PASSWORD:?Set APPLE_APP_SPECIFIC_PASSWORD or NOTARY_KEYCHAIN_PROFILE.}"
    xcrun notarytool submit "$DMG_PATH" \
        --apple-id "$APPLE_ID" \
        --team-id "$APPLE_TEAM_ID" \
        --password "$APPLE_APP_SPECIFIC_PASSWORD" \
        --wait
fi

xcrun stapler staple "$DMG_PATH"
xcrun stapler validate "$DMG_PATH"
