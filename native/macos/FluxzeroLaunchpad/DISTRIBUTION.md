# Fluxzero Launchpad macOS DMG Distribution

Fluxzero Launchpad is built as a universal macOS app targeting macOS 14 and newer.
It is distributed outside the Mac App Store as a signed, notarized, stapled DMG.
This route uses Developer ID signing for Gatekeeper and does not require App Store sandboxing.

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
APP_VERSION=1.3.6 \
APP_BUILD=136 \
FLUXZERO_CLI_ARM64=/path/to/flux-macos-arm64 \
FLUXZERO_CLI_AMD64=/path/to/flux-macos-amd64 \
REQUIRE_BUNDLED_CLI=1 \
CODE_SIGN_IDENTITY="Developer ID Application: Fluxzero B.V. (TEAMID)" \
native/macos/FluxzeroLaunchpad/package-dmg.sh
```

You can also provide a single universal CLI binary:

```sh
APP_VERSION=1.3.6 \
APP_BUILD=136 \
FLUXZERO_CLI_UNIVERSAL=/path/to/fz-universal \
REQUIRE_BUNDLED_CLI=1 \
CODE_SIGN_IDENTITY="Developer ID Application: Fluxzero B.V. (TEAMID)" \
native/macos/FluxzeroLaunchpad/package-dmg.sh
```

The build script signs bundled CLI binaries before signing the app bundle, and signs the DMG when `CODE_SIGN_IDENTITY` is set.

## Notarization

Submit and staple the DMG with either App Store Connect API key credentials:

```sh
APPLE_NOTARY_KEY_ID=ABC123DEFG \
APPLE_NOTARY_ISSUER_ID=00000000-0000-0000-0000-000000000000 \
APPLE_NOTARY_KEY_BASE64="$(base64 -i AuthKey_ABC123DEFG.p8)" \
native/macos/FluxzeroLaunchpad/notarize-dmg.sh native/macos/FluxzeroLaunchpad/build/Fluxzero-Launchpad.dmg
```

Or with an Apple ID app-specific password:

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

## CI Release Signing

The release workflow calls `.github/workflows/macos-launchpad-build.yml` after the macOS native CLI artifacts are built. It imports Apple's public Developer ID G2 intermediate certificate, imports a Developer ID Application `.p12`, packages the universal app, signs the bundled CLI binaries and app bundle with hardened runtime, signs the DMG, submits it to Apple notarization, staples the ticket, verifies the output with `codesign` and `spctl`, and uploads `Fluxzero-Launchpad.dmg`.

Public website download link:

```text
https://github.com/fluxzero-io/fluxzero-cli/releases/latest/download/Fluxzero-Launchpad.dmg
```

Each GitHub Release is still versioned by tag, and the app bundle receives that release version through `CFBundleShortVersionString`.

Configure these GitHub repository secrets:

- `MACOS_DEVELOPER_ID_APPLICATION`: Exact signing identity, for example `Developer ID Application: Fluxzero B.V. (TEAMID)`.
- `MACOS_DEVELOPER_ID_APPLICATION_CERTIFICATE_BASE64`: Base64-encoded exported `.p12` containing the Developer ID Application certificate and private key.
- `MACOS_CERTIFICATE_PASSWORD`: Password used when exporting the `.p12`.
- `MACOS_KEYCHAIN_PASSWORD`: Temporary CI keychain password.

Do not store Apple's Developer ID G2 intermediate as a secret. It is public and the workflow downloads it from Apple's certificate authority site during the build.

Configure one notarization method:

- Preferred API key secrets: `APPLE_NOTARY_KEY_ID`, `APPLE_NOTARY_ISSUER_ID`, `APPLE_NOTARY_KEY_BASE64`.
- Apple ID fallback secrets: `APPLE_ID`, `APPLE_TEAM_ID`, `APPLE_APP_SPECIFIC_PASSWORD`.

Create the certificate in the Apple Developer account under Certificates, Identifiers & Profiles as a Developer ID Application certificate, export it from Keychain Access as `.p12`, then encode it for GitHub:

```sh
base64 -i DeveloperIDApplication.p12 | pbcopy
```

For an App Store Connect API key, encode the `.p8` file the same way:

```sh
base64 -i AuthKey_ABC123DEFG.p8 | pbcopy
```

## App Store/Sandboxing Prep

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
