#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

xmllint --noout \
    "$ROOT_DIR/native/macos/FluxzeroLaunchpad/AppBundle/Info.plist" \
    "$ROOT_DIR/native/windows/FluxzeroLaunchpad/App.xaml" \
    "$ROOT_DIR/native/windows/FluxzeroLaunchpad/MainWindow.xaml" \
    "$ROOT_DIR/native/windows/FluxzeroLaunchpad/SettingsWindow.xaml" \
    "$ROOT_DIR/native/windows/FluxzeroLaunchpad/app.manifest" \
    "$ROOT_DIR/native/windows/FluxzeroLaunchpad/Package.appxmanifest"

if [[ "$(uname -s)" == "Darwin" ]] && command -v swift >/dev/null 2>&1; then
    swift build --package-path "$ROOT_DIR/native/macos/FluxzeroLaunchpad" --scratch-path "${TMPDIR:-/tmp}/fluxzero-launchpad-swift-build"
fi

if command -v dotnet >/dev/null 2>&1; then
    dotnet run --project "$ROOT_DIR/native/windows/FluxzeroLaunchpad.Core.Tests/FluxzeroLaunchpad.Core.Tests.csproj"
    case "$(uname -s)" in
        MINGW*|MSYS*|CYGWIN*)
            dotnet build "$ROOT_DIR/native/windows/FluxzeroLaunchpad/FluxzeroLaunchpad.csproj"
            ;;
        *)
            echo "Skipping Windows app build: run it inside Windows."
            ;;
    esac
else
    echo "Skipping Windows core tests and app build: dotnet is not installed."
fi
