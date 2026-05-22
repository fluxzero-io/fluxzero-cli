#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

xmllint --noout \
    "$ROOT_DIR/native/macos/FluxzeroLaunchpad/AppBundle/Info.plist" \
    "$ROOT_DIR/native/windows/FluxzeroLaunchpad/App.xaml" \
    "$ROOT_DIR/native/windows/FluxzeroLaunchpad/MainWindow.xaml" \
    "$ROOT_DIR/native/windows/FluxzeroLaunchpad/app.manifest" \
    "$ROOT_DIR/native/windows/FluxzeroLaunchpad/Package.appxmanifest"

if [[ "$(uname -s)" == "Darwin" ]] && command -v swift >/dev/null 2>&1; then
    swift build --package-path "$ROOT_DIR/native/macos/FluxzeroLaunchpad" --scratch-path "${TMPDIR:-/tmp}/fluxzero-launchpad-swift-build"
fi

if command -v dotnet >/dev/null 2>&1; then
    dotnet build "$ROOT_DIR/native/windows/FluxzeroLaunchpad/FluxzeroLaunchpad.csproj"
else
    echo "Skipping Windows build: dotnet is not installed."
fi
