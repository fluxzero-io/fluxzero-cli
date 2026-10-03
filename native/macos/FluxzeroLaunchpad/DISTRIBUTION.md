# Fluxzero Launchpad local development

The macOS graphical app is retained as source for local development. CLI releases
no longer build, sign, notarize or publish a Launchpad DMG. The macOS command-line
executables and Homebrew package remain available through the
[CLI installation routes](../../../README.md#installation).

## Local app bundle

```sh
native/macos/FluxzeroLaunchpad/build.sh
```

This creates `native/macos/FluxzeroLaunchpad/build/Fluxzero Launchpad.app`, using
ad-hoc signing for local execution without Developer ID or notarization credentials.
Local builds may omit bundled CLI assets; the app then downloads the managed CLI
into `~/Library/Application Support/Fluxzero/Launchpad/bin/fz`.

## Dependency Simulation

The native app has dependency test overrides for Git and Java:

```sh
FLUXZERO_LAUNCHPAD_SIMULATE_MISSING_GIT=1
FLUXZERO_LAUNCHPAD_SIMULATE_MISSING_JAVA=1
FLUXZERO_LAUNCHPAD_JAVA_INSTALL_DIR=/private/tmp/fluxzero-java-test
FLUXZERO_LAUNCHPAD_JAVA_SOURCE=/path/to/TestJDK.jdk
```

When Java 25+ is missing, Launchpad first checks the managed install location, then installs from `FLUXZERO_LAUNCHPAD_JAVA_SOURCE` when set, and otherwise downloads a Temurin JDK from the Adoptium binary API.
