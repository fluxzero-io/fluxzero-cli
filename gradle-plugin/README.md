# Fluxzero Gradle Plugin

Gradle plugin for Fluxzero projects. It provides the project-local dev environment and synchronizes AI agent
instruction files from GitHub releases.

## Features

- **Automatic SDK Version Detection**: Detects Fluxzero SDK version from your dependencies
- **Automatic Language Detection**: Identifies project language (Kotlin or Java)
- **Lifecycle Integration**: Runs automatically before compilation
- **Multi-Module Support**: Configurable to run only on root project or all modules
- **Smart Caching**: Only downloads when version changes
- **Local Dev Environment**: Starts the same runtime, proxy, IDP, reload, test, frontend, diagnostics, and MCP stack as `fz dev`
- **Gradle-Correct Builds**: Gradle owns compilation, annotation processors, resources, and runtime/test classpaths
- **Layered Package Publishing**: Publishes application outputs above ordered dependency layers without requiring Docker

## Quick Start

### Installation

Add the plugin to your `build.gradle.kts`:

```kotlin
plugins {
    id("io.fluxzero.tools.gradle.plugin") version "1.0.0"
}
```

That's it! The plugin will automatically detect your SDK version and language, then sync agent files before compilation.

## Local Development

Apply the plugin to the root project and run:

```bash
./gradlew fluxzeroDev
```

The task starts the environment independently and attaches a live event view. Type `q`/`quit` and press Enter to open
a menu, use the arrow keys to choose between detaching, stopping the environment, and returning to the view, and press
Enter to confirm. Type `d`/`detach` and press Enter to leave it running. `Ctrl-C` stops the environment and all
applications; an unexpected terminal disconnect only detaches the view.
Skip the attached view entirely with background mode:

```bash
./gradlew fluxzeroDev -Pfluxzero.dev.background=true
fz dev status
fz dev attach
fz dev logs --follow
fz dev stop
```

The same launch settings are available as native task options, for example
`./gradlew fluxzeroDev --background --applications=api,worker --no-tests`. Run
`./gradlew help --task fluxzeroDev` for the complete, locally installed option reference.

Only one dev session can run per project. The test runtime is currently in-memory, so a stopped or unexpectedly killed
session loses its data and replays startup commands on the next launch. A detached environment keeps its processes and
memory alive until `fz dev stop`.

Shared defaults normally belong in `.fluxzero/dev.yaml`. Gradle DSL overrides are available when build-owned
configuration is preferable:

```kotlin
fluxzero {
    dev {
        applications.set(listOf("app", "audittrail"))
        environment.set("local")
        port.set(4200)
        idp.set("external")
        frontendCommand.set("cd frontend && npm run dev -- --port {port}")
        backendPaths.set(listOf("/api", "/webhooks"))
        testsEnabled.set(true)
        background.set(false)
    }
}
```

| Setting | Gradle property | Default |
|---------|-----------------|---------|
| `serverVersion` | explicit DSL or `FLUXZERO_DEV_SERVER_VERSION` | active project pin or newest stable `1.x` release |
| `mainClass` | `fluxzero.dev.mainClass` | auto-detected |
| `applicationName` | `fluxzero.dev.applicationName` | project name |
| `applications` | `fluxzero.dev.applications` (comma-separated) | all discovered apps |
| `environment` | `fluxzero.dev.environment` | `local` |
| `port` | `fluxzero.dev.port` | dynamic |
| `idp` | `fluxzero.dev.idp` | `managed` |
| `namespace` | `fluxzero.dev.namespace` | project default |
| `watch` | `fluxzero.dev.watch` | `true` |
| `compileOnStart` | `fluxzero.dev.compileOnStart` | `true` |
| `testsEnabled` | `fluxzero.dev.testsEnabled` | `true` |
| `fastCompiler` | `fluxzero.dev.fastCompiler` | `false`; Maven-only optimization |
| `frontendCommand` / `frontendUrl` | `fluxzero.dev.frontendCommand` / `fluxzero.dev.frontendUrl` | none |
| `frontendDirectory` | `fluxzero.dev.frontendDirectory` | project root |
| `frontendSetupCommand` | `fluxzero.dev.frontendSetupCommand` | none |
| `frontendEnabled` | `fluxzero.dev.frontendEnabled` | `true` |
| `backendPaths` / `appArgs` | comma-separated properties with the same names | empty |
| `startupTimeoutMillis` | `fluxzero.dev.startupTimeoutMillis` | `20000` |
| `gracefulShutdownTimeoutMillis` | `fluxzero.dev.gracefulShutdownTimeoutMillis` | `5000` |
| `debounceMillis` | `fluxzero.dev.debounceMillis` | `300` |
| `background` | `fluxzero.dev.background` | `false` |

`fluxzeroDevMetadata` is the build contract used internally by the dev server. It compiles main and test source sets,
then writes application outputs and classpaths to ignored `.fluxzero/dev/gradle-metadata.json`. It can also be run
directly when troubleshooting Gradle discovery:

```bash
./gradlew fluxzeroDevMetadata
```

## Package publishing

`fluxzeroPublishPackage` builds a Java OCI image from Gradle's main source-set outputs and runtime classpath, then pushes
it directly through the registry protocol. Dependencies form the stable lower layer and application classes/resources
form the top layer. A Spring Boot `Start-Class` or regular JAR `Main-Class` is detected automatically; configure
`mainClass` only when the build artifact does not identify one.

```kotlin
fluxzero {
    packagePublishing {
        packageName.set("my-service")
        images.add("registry.fluxzero.io/\${organisationId}/\${packageName}")
        tags.add(providers.environmentVariable("GITHUB_SHA").map { "sha-$it" })
        authentications {
            create("fluxzero") {
                host.set("registry.fluxzero.io")
                githubOidc {
                    audience.set("https://cloud.fluxzero.io")
                }
            }
        }
    }
}
```

Run the build and publisher as separate CI steps when tests should complete before requesting short-lived credentials:

```bash
./gradlew --no-daemon build
./gradlew --no-daemon fluxzeroPublishPackage
```

GitHub OIDC authentication requires `id-token: write` on the job. The plugin requests the OIDC token only when the
publish task runs. For an image containing `${organisationId}`, it discovers the Fluxzero identity endpoint from the
registry's standard Bearer challenge, resolves the organisation from that token, and substitutes the result. The token
is not sent to the unauthenticated registry discovery request.

| Name | Required | Default | Description |
|------|----------|---------|-------------|
| `packageName` | No | Gradle project name | Public package name and value for `${packageName}`. |
| `packageVersion` | No | Gradle project version | Primary image tag when `tags` is empty. |
| `applicationId` | No | — | Fluxzero application id stored as OCI metadata. |
| `mainClass` | No | Built artifact `Start-Class` or `Main-Class` | Java application entrypoint. |
| `images` | Yes | — | Image repositories; supports complete `${organisationId}` and `${packageName}` path segments. |
| `tags` | No | `packageVersion` | Tags applied to every configured image. |
| `baseImage` | No | Fluxzero Java distroless runtime | Runtime base image. |
| `baseImageSource` | No | `registry` | `registry` or `docker-daemon`. |
| `javaToolOptions` | No | Fluxzero JVM defaults | Value stored as `JAVA_TOOL_OPTIONS`. |
| `labels` | No | Empty | Additional or overriding OCI labels. |
| `publishAttempts` | No | `10` | Attempts for transient registry failures. |
| `publishRetryDelayMillis` | No | `2000` | Base delay between retry attempts. |
| `authentications` | No | Anonymous access | Host-bound credentials; unmatched image registries remain anonymous. |

Registry HTTP operations time out after 60 seconds by default. Set the JVM system property
`jib.httpTimeout` to a different number of milliseconds to override this for the build.

Each named authentication requires an exact lowercase `host` (including the port when non-default) and exactly one
mechanism:

```kotlin
authentications {
    create("privateRegistry") {
        host.set("registry.example.com")
        basic {
            username.set(providers.environmentVariable("REGISTRY_USERNAME"))
            token.set(providers.environmentVariable("REGISTRY_TOKEN"))
        }
    }
}
```

| Name | Required | Default | Description |
|------|----------|---------|-------------|
| `authentication.host` | Yes | — | Exact registry host and optional port, without a scheme or path. |
| `authentication.basic` | No | — | Username/token credential; mutually exclusive with `githubOidc`. |
| `authentication.githubOidc` | No | — | GitHub Actions OIDC credential; mutually exclusive with `basic`. |
| `basic.username` | No | Empty | Registry username. |
| `basic.token` | Yes | — | Registry password or token. |
| `githubOidc.username` | No | Empty | Registry username associated with the OIDC token. |
| `githubOidc.audience` | Yes | — | Audience requested from GitHub Actions. |

## Configuration

### Minimal Configuration (Recommended)

Everything is auto-detected by default:

```kotlin
plugins {
    id("io.fluxzero.tools.gradle.plugin") version "1.0.0"
}

// No additional configuration needed!
```

### All Configuration Options

```kotlin
fluxzero {
    projectFiles {
        // Master switch. Set to false to keep the plugin configured but skip syncing (default: true).
        enabled.set(true)

        // Multi-module guard. Keep true to sync once from the Gradle root project (default: true).
        rootProjectOnly.set(true)

        // Re-download and rewrite files even when the local sync metadata is current (default: false).
        forceUpdate.set(false)

        // Use only when language detection is wrong or unavailable. Accepted values: "kotlin" or "java".
        overrideLanguage.set("kotlin") // or "java"

        // Use only when the Fluxzero SDK version cannot be inferred from dependencies, BOMs, or properties.
        overrideSdkVersion.set("1.75.1")
    }
}
```

Every setting is optional. The plugin auto-detects the language and SDK version in the common case.

| Setting | Command-line property | Default | When to use it |
|---------|-----------------------|---------|----------------|
| `enabled` | `fluxzero.projectFiles.enabled` | `true` | Disable all plugin work without removing the plugin from the build file. |
| `rootProjectOnly` | `fluxzero.projectFiles.rootProjectOnly` | `true` | Sync only from the root project. Set `false` if every module needs its own files. |
| `forceUpdate` | `fluxzero.projectFiles.forceUpdate` | `false` | Force a fresh download when files look stale or you want to refresh local generated files. |
| `overrideLanguage` | `fluxzero.projectFiles.overrideLanguage` | auto-detected | Set to `kotlin` or `java` only when automatic language detection picks the wrong target. |
| `overrideSdkVersion` | `fluxzero.projectFiles.overrideSdkVersion` | auto-detected | Pin the SDK version when dependencies, BOMs, or properties do not expose it. |

### Disabling the Plugin

```kotlin
fluxzero {
    projectFiles {
        enabled.set(false)
    }
}
```

You can also disable it via command line:

```bash
./gradlew build -Pfluxzero.projectFiles.enabled=false
```

The same pattern works for the other settings, for example:

```bash
./gradlew syncProjectFiles -Pfluxzero.projectFiles.forceUpdate=true
./gradlew build -Pfluxzero.projectFiles.overrideLanguage=kotlin
./gradlew build -Pfluxzero.projectFiles.overrideSdkVersion=1.75.1
./gradlew build -Pfluxzero.projectFiles.rootProjectOnly=false
```

## How It Works

### Automatic Detection

The plugin automatically:

1. **Detects SDK Version** from:
   - `gradle/libs.versions.toml` (version catalog)
   - `build.gradle.kts` (direct dependency declaration)
   - `build.gradle` (Groovy DSL)

2. **Detects Language** by checking for:
   - Kotlin plugin → Kotlin
   - Java plugin → Java

3. **Downloads Agent Files** from GitHub releases matching your SDK version

4. **Extracts to `.fluxzero/agents/` directory** in your project root. The plugin creates `.fluxzero` when it does not exist, and updates only the `agents` subdirectory.

### Lifecycle Integration

The `syncProjectFiles` task runs automatically before:
- `compileJava` (if Java plugin is applied)
- `compileKotlin` (if Kotlin plugin is applied)

You can also run it manually:

```bash
./gradlew syncProjectFiles
```

## Multi-Module Projects

By default, agent files are only synced in the root project to avoid duplication:

```kotlin
// Root build.gradle.kts
plugins {
    id("io.fluxzero.tools.gradle.plugin") version "1.0.0"
}

fluxzero {
    projectFiles {
        rootProjectOnly.set(true) // default behavior
    }
}
```

To sync in every module:

```kotlin
fluxzero {
    projectFiles {
        rootProjectOnly.set(false)
    }
}
```

## Troubleshooting

### Plugin Not Detecting SDK Version

**Problem**: You see a message like "No SDK version detected"

When the plugin cannot detect a released Fluxzero SDK version, it logs a warning and skips project-files sync. The build continues.

**Solutions**:

1. Ensure you have a Fluxzero SDK dependency:

```kotlin
dependencies {
    implementation("io.fluxzero:fluxzero-sdk:1.75.1")
}
```

2. Or use a BOM:

```kotlin
dependencies {
    implementation(platform("io.fluxzero:fluxzero-bom:1.75.1"))
    implementation("io.fluxzero:fluxzero-sdk")
}
```

3. Or manually override:

```kotlin
fluxzero {
    projectFiles {
        overrideSdkVersion.set("1.75.1")
    }
}
```

### Local Snapshot SDK Versions

**Problem**: You are testing a locally built SDK such as `0-SNAPSHOT`.

Snapshot versions do not have matching release artifacts with project files, so the plugin skips sync and lets the build continue.
To sync project files anyway, temporarily point `overrideSdkVersion` at a released SDK version.

### GitHub Release or Asset Unavailable

**Problem**: The matching GitHub release or project-files asset is unavailable, or GitHub returns an API error.

Project-files sync is optional. The plugin logs a warning, skips sync, and lets the build continue.

### Wrong Language Detected

**Problem**: Plugin detects Java but you're using Kotlin (or vice versa)

**Solution**: Override the language:

```kotlin
fluxzero {
    projectFiles {
        overrideLanguage.set("kotlin") // or "java"
    }
}
```

### Files Not Updating

**Problem**: Agent files are outdated after upgrading SDK version

**Solution**: Force an update:

```bash
./gradlew syncProjectFiles --rerun-tasks
```

Or configure force update:

```kotlin
fluxzero {
    projectFiles {
        forceUpdate.set(true)
    }
}
```

### Plugin Runs in Submodules

**Problem**: Plugin syncs files in every module, causing duplication

**Solution**: Ensure `rootProjectOnly` is enabled (default):

```kotlin
fluxzero {
    projectFiles {
        rootProjectOnly.set(true)
    }
}
```

## Task Reference

### `fluxzeroPublishPackage`

Builds and publishes a layered Java OCI package from Gradle's main source-set output and ordered runtime JARs.

```bash
./gradlew fluxzeroPublishPackage
```

### `syncProjectFiles`

Synchronizes AI agent instruction files for the project.

**Usage**:
```bash
./gradlew syncProjectFiles
```

**Inputs**:
- Project directory
- SDK version (auto-detected or overridden)
- Language (auto-detected or overridden)

**Outputs**:
- `.fluxzero/agents/` directory with agent files

**Task Properties**:
- `enabled`: Whether the task should run
- `forceUpdate`: Force re-download even if files exist
- `projectDir`: Project directory to sync files to
- `sdkVersion`: SDK version to use
- `language`: Language variant to download

## Examples

### Basic Kotlin Project

```kotlin
plugins {
    kotlin("jvm") version "1.9.0"
    id("io.fluxzero.tools.gradle.plugin") version "1.0.0"
}

dependencies {
    implementation("io.fluxzero:fluxzero-sdk:1.75.1")
}

// No additional configuration needed - everything auto-detected!
```

### Basic Java Project

```kotlin
plugins {
    java
    id("io.fluxzero.tools.gradle.plugin") version "1.0.0"
}

dependencies {
    implementation("io.fluxzero:fluxzero-sdk:1.75.1")
}
```

### Multi-Module Project

```kotlin
// Root build.gradle.kts
plugins {
    id("io.fluxzero.tools.gradle.plugin") version "1.0.0" apply false
}

// In root project only
apply(plugin = "io.fluxzero.tools.gradle.plugin")

fluxzero {
    projectFiles {
        rootProjectOnly.set(true) // Sync only in root
    }
}
```

### With Version Catalog

```toml
# gradle/libs.versions.toml
[versions]
fluxzero = "1.75.1"

[libraries]
fluxzero-sdk = { module = "io.fluxzero:fluxzero-sdk", version.ref = "fluxzero" }

[plugins]
fluxzero-tools = { id = "io.fluxzero.tools.gradle.plugin", version = "1.0.0" }
```

```kotlin
// build.gradle.kts
plugins {
    alias(libs.plugins.fluxzero.tools)
}

dependencies {
    implementation(libs.fluxzero.sdk)
}
```

### Manual Override Configuration

```kotlin
plugins {
    id("io.fluxzero.tools.gradle.plugin") version "1.0.0"
}

fluxzero {
    projectFiles {
        // Bypass all auto-detection
        overrideLanguage.set("kotlin")
        overrideSdkVersion.set("1.75.1")

        // Force update on every build (not recommended for CI)
        forceUpdate.set(false)
    }
}
```

## Requirements

- Gradle 7.0 or later
- Java 11 or later
- Internet connection to sync agent files from GitHub; builds continue without syncing if GitHub is unavailable

## Support

For issues and questions:
- GitHub Issues: [flux-capacitor/flux-cli](https://github.com/flux-capacitor/flux-cli/issues)
- Documentation: [Fluxzero Docs](https://docs.fluxzero.io)

## License

See the main project LICENSE file.
