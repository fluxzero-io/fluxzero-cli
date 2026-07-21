#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
package_script="$script_dir/../native/macos/FluxzeroLaunchpad/package-dmg.sh"

FLUXZERO_DMG_FUNCTIONS_ONLY=1 source "$package_script"

sleep() {
    :
}

calls=0
forced=0
hdiutil() {
    calls=$((calls + 1))
    if [[ "${2:-}" == "-force" ]]; then
        forced=1
        return 0
    fi
    ((calls >= 3))
}

DMG_DETACH_ATTEMPTS=5 DMG_DETACH_RETRY_SECONDS=0 detach_disk_image disk4
[[ "$calls" == "3" ]]
[[ "$forced" == "0" ]]

calls=0
forced=0
hdiutil() {
    calls=$((calls + 1))
    if [[ "${2:-}" == "-force" ]]; then
        forced=1
        return 0
    fi
    return 16
}

DMG_DETACH_ATTEMPTS=3 DMG_DETACH_RETRY_SECONDS=0 detach_disk_image disk4 2>/dev/null
[[ "$calls" == "4" ]]
[[ "$forced" == "1" ]]

calls=0
hdiutil() {
    calls=$((calls + 1))
    return 16
}

if DMG_DETACH_ATTEMPTS=2 DMG_DETACH_RETRY_SECONDS=0 detach_disk_image disk4 2>/dev/null; then
    echo "Detach unexpectedly succeeded after the forced fallback failed" >&2
    exit 1
fi
[[ "$calls" == "3" ]]
