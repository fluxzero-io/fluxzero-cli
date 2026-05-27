# Fluxzero Launchpad macOS Distribution

Fluxzero Launchpad is built as a universal macOS app targeting macOS 14 and newer.

## Local DMG

```sh
native/macos/FluxzeroLaunchpad/package-dmg.sh
```

Local builds may omit bundled CLI assets. In that case the app falls back to the managed CLI download path in `~/Library/Application Support/Fluxzero/Launchpad/bin/fz`.
When Java 25+ is missing, the app downloads a managed Temurin JDK on first project generation and installs it at `~/Library/Java/JavaVirtualMachines/fluxzero-temurin-25.jdk`.
The staging image defaults to `DMG_SIZE=256m`, which leaves room for the universal app and bundled native CLI binaries.

## Release DMG

Package the native Fluxzero CLI inside the app and require it for release builds:

```sh
FLUXZERO_CLI_ARM64=/path/to/flux-macos-arm64 \
FLUXZERO_CLI_AMD64=/path/to/flux-macos-amd64 \
REQUIRE_BUNDLED_CLI=1 \
CODE_SIGN_IDENTITY="Developer ID Application: Fluxzero B.V. (TEAMID)" \
native/macos/FluxzeroLaunchpad/package-dmg.sh
```

You can also provide a single universal CLI binary:

```sh
FLUXZERO_CLI_UNIVERSAL=/path/to/fz-universal \
REQUIRE_BUNDLED_CLI=1 \
CODE_SIGN_IDENTITY="Developer ID Application: Fluxzero B.V. (TEAMID)" \
native/macos/FluxzeroLaunchpad/package-dmg.sh
```

The build script signs bundled CLI binaries before signing the app bundle, and signs the DMG when `CODE_SIGN_IDENTITY` is set.

## Notarization

After Apple Developer enrollment is active, submit and staple the DMG:

```sh
APPLE_ID=you@example.com \
APPLE_TEAM_ID=TEAMID \
APPLE_APP_SPECIFIC_PASSWORD=xxxx-xxxx-xxxx-xxxx \
native/macos/FluxzeroLaunchpad/notarize-dmg.sh native/macos/FluxzeroLaunchpad/build/Fluxzero-Launchpad.dmg
```

Or use a stored notarytool profile:

```sh
NOTARY_KEYCHAIN_PROFILE=fluxzero-notary \
native/macos/FluxzeroLaunchpad/notarize-dmg.sh native/macos/FluxzeroLaunchpad/build/Fluxzero-Launchpad.dmg
```

## Sandboxing Prep

The app is not sandboxed yet. A sandboxed build should grant the app access to one user-selected project root, store that access as a security-scoped bookmark, and only generate projects under that root. The bundled CLI then inherits the app sandbox, so the CLI must only read bundled resources, use network access declared by entitlement, and write inside the security-scoped project location.

A starter entitlement file is included at `AppBundle/FluxzeroLaunchpad.sandbox.entitlements`, but do not enable it for release until security-scoped bookmark storage is implemented and verified.

## Dependency Simulation

The native app has dependency test overrides for Git and Java:

```sh
FLUXZERO_LAUNCHPAD_SIMULATE_MISSING_GIT=1
FLUXZERO_LAUNCHPAD_SIMULATE_MISSING_JAVA=1
FLUXZERO_LAUNCHPAD_JAVA_INSTALL_DIR=/private/tmp/fluxzero-java-test
FLUXZERO_LAUNCHPAD_JAVA_SOURCE=/path/to/TestJDK.jdk
```

When Java 25+ is missing, Launchpad first checks the managed install location, then installs from `FLUXZERO_LAUNCHPAD_JAVA_SOURCE` when set, and otherwise downloads a Temurin JDK from the Adoptium binary API.
