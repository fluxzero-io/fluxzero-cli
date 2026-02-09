# Fluxzero Gradle Plugin

Gradle plugin for Fluxzero projects that automatically synchronizes AI agent instruction files from GitHub releases.

## Features

- **Automatic SDK Version Detection**: Detects Fluxzero SDK version from your dependencies
- **Automatic Language Detection**: Identifies project language (Kotlin or Java)
- **Lifecycle Integration**: Runs automatically before compilation
- **Multi-Module Support**: Configurable to run only on root project or all modules
- **Smart Caching**: Only downloads when version changes

## Quick Start

### Installation

Add the plugin to your `build.gradle.kts`:

```kotlin
plugins {
    id("io.fluxzero.tools.gradle") version "1.0.0"
}
```

That's it! The plugin will automatically detect your SDK version and language, then sync agent files before compilation.

## Configuration

### Minimal Configuration (Recommended)

Everything is auto-detected by default:

```kotlin
plugins {
    id("io.fluxzero.tools.gradle") version "1.0.0"
}

// No additional configuration needed!
```

### All Configuration Options

```kotlin
fluxzero {
    projectFiles {
        // Enable or disable the plugin (default: true)
        enabled.set(true)

        // Only run on root project in multi-module builds (default: true)
        rootProjectOnly.set(true)

        // Force re-download even if files exist (default: false)
        forceUpdate.set(false)

        // Override auto-detected language (optional)
        overrideLanguage.set("kotlin") // or "java"

        // Override auto-detected SDK version (optional)
        overrideSdkVersion.set("1.75.1")
    }
}
```

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

4. **Extracts to `.fluxzero/` directory** in your project root

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
    id("io.fluxzero.tools.gradle") version "1.0.0"
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
- `.fluxzero/` directory with agent files

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
    id("io.fluxzero.tools.gradle") version "1.0.0"
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
    id("io.fluxzero.tools.gradle") version "1.0.0"
}

dependencies {
    implementation("io.fluxzero:fluxzero-sdk:1.75.1")
}
```

### Multi-Module Project

```kotlin
// Root build.gradle.kts
plugins {
    id("io.fluxzero.tools.gradle") version "1.0.0" apply false
}

// In root project only
apply(plugin = "io.fluxzero.tools.gradle")

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
fluxzero-tools = { id = "io.fluxzero.tools.gradle", version = "1.0.0" }
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
    id("io.fluxzero.tools.gradle") version "1.0.0"
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
- Internet connection (for downloading agent files from GitHub)

## Support

For issues and questions:
- GitHub Issues: [flux-capacitor/flux-cli](https://github.com/flux-capacitor/flux-cli/issues)
- Documentation: [Fluxzero Docs](https://docs.fluxzero.io)

## License

See the main project LICENSE file.
