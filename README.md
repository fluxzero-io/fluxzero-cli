# fluxzero-cli 

A command-line interface for [Flux](https://fluxcapacitor.io/) that helps you scaffold new projects, perform dependency upgrades, generate example code, and manage Flux Cloud resources.

## Installation

### Option 1: Automated Installation (Recommended)

**Unix/Linux/macOS:**
```bash
curl -sSL https://github.com/fluxzero-io/fluxzero-cli/releases/latest/download/install.sh | sh
```

**Windows PowerShell:**
```powershell
iwr -useb https://github.com/fluxzero-io/fluxzero-cli/releases/latest/download/install.ps1 | iex
```

The installer will:
- 🎯 **Auto-detect** your platform and architecture
- 📦 **Download native binary** (no Java required)
- ⚡ **Install to `~/.fluxzero/bin/fz`** 
- 🔗 **Add to PATH** (optional - you'll be prompted)
- ✅ **Verify installation** with test commands

### Option 2: Manual Native Executable Download

Download the native executable for your platform from the [releases page](https://github.com/fluxzero-io/fluxzero-cli/releases):

- **Linux x86_64**: `flux-linux-amd64`
- **macOS Intel**: `flux-macos-amd64`
- **macOS Apple Silicon**: `flux-macos-arm64`
- **Windows x64**: `flux-windows-amd64.exe`

```bash
# Example for macOS Apple Silicon
curl -L -o fz https://github.com/fluxzero-io/fluxzero-cli/releases/latest/download/flux-macos-arm64
chmod +x fz
sudo mv fz /usr/local/bin/fz
```

**Benefits of native executables:**
- ⚡ **5x faster startup** (~0.27s vs ~1.36s for JAR)
- 📦 **Self-contained** (no Java installation required)
- 🚀 **Instant execution** (no JVM warm-up time)
- 🌍 **Cross-platform** (Linux, macOS, Windows)

### Option 3: Manual JAR Installation (Legacy)

1. Download the latest `fluxzero-cli.jar` from the [releases page](https://github.com/fluxzero-io/fluxzero-cli/releases)
2. Run it with Java: `java -jar fluxzero-cli.jar`

## Uninstallation

To remove FluxZero CLI from your system:

**Unix/Linux/macOS:**
```bash
curl -sSL https://github.com/fluxzero-io/fluxzero-cli/releases/latest/download/uninstall.sh | sh
```

**Windows PowerShell:**
```powershell
iwr -useb https://github.com/fluxzero-io/fluxzero-cli/releases/latest/download/uninstall.ps1 | iex
```

The uninstaller will:
- 🔍 **Detect installations** (both current `.fluxzero` and legacy `.flux` directories)
- 📋 **Show what will be removed** with confirmation prompts
- 🗑️ **Clean removal** of binaries, directories, and PATH entries
- ✨ **Complete cleanup** leaves no traces

Add `--force` (Unix) or `-Force` (Windows) to skip confirmation prompts.

## Project Versioning

The fluxzero-cli uses per-project versioning rather than global installation. Each project determines which version of the CLI it uses in the `.flux/config.yaml` file.

## Usage

Once installed, you can use the CLI with the `fz` command:

```bash
# Initialize a new project (interactive template selection)
fz init my-project

# List available templates
fz templates list

# Build and publish the current Maven project as a Fluxzero package
FLUXZERO_REGISTRY_TOKEN=... fz publish

# Show version
fz version

# Upgrade CLI to latest version
fz upgrade
```

### CLI Commands & Parameters

#### `fz init` - Initialize a new project

**Basic usage:**
```bash
fz init [OPTIONS]
```

**Options:**

| Parameter | Description | Example |
|-----------|-------------|---------|
| `--template` | Name of the template to use | `--template flux-kotlin-single` |
| `--template-path` | Path to custom template directory or ZIP file | `--template-path ./my-templates` |
| `--name` | Project name (1-50 chars: 0-9, a-z, -, _) | `--name my-app` |
| `--dir` | Directory to create project in | `--dir ./projects` |
| `--package` | Java package name | `--package com.example.myapp` |
| `--group-id` | Maven/Gradle group ID | `--group-id com.example` |
| `--artifact-id` | Maven/Gradle artifact ID | `--artifact-id my-app` |
| `--application-id` | Fluxzero application ID to configure for package publishing | `--application-id app-...` |
| `--description` | Project description | `--description "My application"` |
| `--build` | Build system (`maven` or `gradle`) | `--build gradle` |
| `--git` | Initialize Git repository | `--git` |

#### `fz publish` - Build and publish a Java application package

`fz publish` currently supports Maven Java projects. It runs Maven to build the project and collect runtime
dependencies, then uses the shared Fluxzero publisher to build and publish a layered Java OCI package. The registry host
defaults to `registry.fluxzero.io`; override it only for local development or non-standard environments.

```bash
FLUXZERO_REGISTRY_TOKEN=... fz publish --package-name my-app
```

When no package version is configured, `fz publish` generates one from git branch, UTC timestamp, and commit SHA. Dirty
worktrees are refused unless `--allow-dirty` is set; dirty pushes get a `-dirty` tag suffix.

Useful options:

| Parameter | Description | Example |
|-----------|-------------|---------|
| `--project-dir` | Project directory | `--project-dir ./my-app` |
| `--registry-host` | Registry host override | `--registry-host https://registry.example.com` |
| `--registry-token` | Registry token, usually provided through `FLUXZERO_REGISTRY_TOKEN` | `--registry-token ...` |
| `--package-name` | Package name, required unless `FLUXZERO_PACKAGE_NAME` is set | `--package-name my-app` |
| `--package-version` | Package version override, defaults to a generated git/time-based tag | `--package-version run-123-abc1234` |
| `--allow-dirty` | Allow publishing uncommitted local changes and mark the tag with `-dirty` | `--allow-dirty` |
| `--application-id` | Fluxzero application id stored as OCI metadata | `--application-id ...` |
| `--main-class` | Main class override when the JAR manifest does not expose one | `--main-class com.example.Application` |
| `--base-image` | Java runtime base image override | `--base-image eclipse-temurin:21-jre` |
| `--base-image-source` | Base image source: `registry` or `docker-daemon` | `--base-image-source docker-daemon` |
| `--java-tool-options` | Value for `JAVA_TOOL_OPTIONS`; defaults to the process env var or Fluxzero JVM defaults | `--java-tool-options "-Xmx2g"` |
| `--skip-build` | Publish existing `target/classes` and `target/fluxzero-dependencies` output | `--skip-build` |

If a custom Dockerfile is needed, build it before `fz publish`, tag it as a base image, and pass that tag with
`--base-image`. Use `--base-image-source docker-daemon` when that tag only exists in the local Docker daemon. Custom
base images must provide `/usr/bin/java`.

`--java-tool-options` is written to the package as `JAVA_TOOL_OPTIONS`. If the option is omitted, `fz publish` uses the
process `JAVA_TOOL_OPTIONS` value when it exists, otherwise it uses Fluxzero JVM defaults.

Generated Maven projects set `project.build.outputTimestamp` to a fixed ZIP-safe timestamp. The publisher also writes
deterministic OCI creation and file modification timestamps so unchanged layers are more likely to keep the same digest.

**`fz init` examples:**

```bash
# Interactive mode (prompts for all options)
fz init

# With built-in template
fz init --template flux-kotlin-single --name my-app --package com.example.myapp --build gradle

# Using custom template directory
fz init --template-path ./my-templates --template custom-template --name my-project

# Using custom template ZIP file
fz init --template-path ./templates/my-template.zip --template my-template --name my-project

# Full example with all options
fz init \
  --template flux-java-single \
  --name awesome-app \
  --dir ./workspace \
  --package com.company.awesome \
  --group-id com.company \
  --artifact-id awesome-app \
  --description "An awesome application" \
  --build maven \
  --git
```

#### Custom Templates

The `--template-path` parameter allows you to use templates from:

1. **Local directory containing multiple templates:**
   ```bash
   # Directory structure:
   # my-templates/
   # ├── web-template/
   # ├── api-template/
   # └── cli-template/
   fz init --template-path ./my-templates --template web-template
   ```

2. **Single ZIP file template:**
   ```bash
   fz init --template-path ./my-template.zip --template my-template
   ```

Templates should follow the same structure as built-in templates with optional `refactor.yaml` for customization.

#### `fz templates list` - List available templates

```bash
# List built-in templates
fz templates list

# List templates from custom directory (not currently supported - use fz init with --template-path)
```

#### `fz version` - Show version information

```bash
fz version
```

#### `fz upgrade` - Upgrade CLI to latest version

```bash
fz upgrade
```

See `fz --help` or `fz <command> --help` for detailed help on any command.

## Installation Location

FluxZero CLI installs to:
- **Directory**: `~/.fluxzero/bin/fz` (or `fz.exe` on Windows)
- **PATH Integration**: 
  - **Unix**: `/usr/local/bin/fz` → `~/.fluxzero/bin/fz`
  - **Windows**: `~/.fluxzero/bin` added to user PATH
- **Legacy Support**: Also detects and can upgrade from old `.flux/fluxzero-cli.jar` installations

## Requirements

- **Native executables**: No requirements (self-contained)
- **JAR version**: Java 21 or higher

## Templates

Templates are sourced from the external repository [fluxzero-examples](https://github.com/fluxzero-io/fluxzero-examples) at build time. The build downloads the templates ZIP and packages them for use by the CLI. There is no fallback to in-repo templates or git; if downloading fails, the build fails. The downloaded archive is cached under `templates/build/examples-snapshot` and reused on subsequent builds unless you force a refresh.

**Template features:**
- Package name replacement
- File removal based on configuration
- Line-by-line content modification
- File permission management
- Interactive customization during project creation

Available templates (see the examples repo for the latest list):
- `flux-kotlin-single` - Single-module Kotlin project
- `flux-java-single` - Single-module Java project
- `gamerental` - Example multi-feature application

### Template Customization with `refactor.yaml`

Templates can include a `refactor.yaml` file to customize the generated project. This file defines operations that are applied during project initialization.

#### Supported Operations

**`replace` - Text replacement**
```yaml
- type: replace
  files: ["**/*.kt", "**/*.java"]     # Glob patterns for files to modify
  find: "com\\.example\\.template"    # Text or regex to find
  replace: "${package}"               # Replacement text (supports variables)
  regex: true                         # Whether to use regex matching (default: false)
```

**`delete` - File removal**
```yaml
- type: delete
  files: ["**/*.tmp", "build/"]       # Glob patterns for files/directories to delete
```

**`rename` - File/directory renaming**
```yaml
- type: rename
  from: "src/main/kotlin/com/example/template"
  to: "src/main/kotlin/${packagePath}"
```

**`createDirectory` - Directory creation**
```yaml
- type: createDirectory
  directory: "logs"                   # Directory path (supports variables)
```

**`chmod` - File permission management**
```yaml
- type: chmod
  files: ["gradlew", "scripts/*.sh"]  # Glob patterns for files to modify
  mode: "755"                         # Standard Unix permissions: 755 (executable), 644 (read-only), 777 (full access)
```

**`cleanupEmptyDirectories` - Remove empty directories**
```yaml
- type: cleanupEmptyDirectories
  paths: ["src/main", "src/test"]     # Directory paths to clean (default: ["src/main", "src/test"])
```

#### Variable Substitution

The following variables are available for use in `replace`, `rename`, `createDirectory` operations:

- `${package}` - Java package name (e.g., `com.example.myapp`)
- `${packagePath}` - Package as file path (e.g., `com/example/myapp`)
- `${projectName}` - Project name
- `${groupId}` - Maven/Gradle group ID
- `${artifactId}` - Maven/Gradle artifact ID
- `${description}` - Project description

Variables can be used in either `${variable}` or `{{variable}}` format.

#### Example `refactor.yaml`

```yaml
operations:
  # Replace package names in source files
  - type: replace
    files: ["**/*.kt"]
    find: "package com\\.example\\.template"
    replace: "package ${package}"
    regex: true

  # Update build files
  - type: replace
    files: ["pom.xml", "build.gradle.kts"]
    find: "com.example.template"
    replace: "${package}"

  # Make scripts executable
  - type: chmod
    files: ["gradlew", "scripts/*.sh"]
    mode: "755"

  # Make config files read-only
  - type: chmod
    files: ["config/*.conf"]
    mode: "644"

  # Rename package directories
  - type: rename
    from: "src/main/kotlin/com/example/template"
    to: "src/main/kotlin/${packagePath}"

  # Clean up temporary files
  - type: delete
    files: ["**/*.tmp"]

  # Create log directory
  - type: createDirectory
    directory: "logs"
```

Advanced:
- Override repo URL: `./gradlew -PexamplesRepoUrl=https://github.com/your-org/your-examples.git build`
- Override release tag: `./gradlew -PexamplesReleaseTag=v1.2.3 build`
- Pin an explicit ZIP: `./gradlew -PexamplesZipUrl=https://example.com/templates.zip build`
- Force refresh the cache: `./gradlew -PrefreshExamples=true build`

## Build Plugins

Fluxzero provides Gradle and Maven plugins that automatically sync AI agent instruction files (AGENTS.md, CLAUDE.md, etc.) from GitHub releases matching your Fluxzero SDK version.

### Gradle Plugin

**build.gradle.kts**
```kotlin
plugins {
    id("io.fluxzero.tools.gradle.plugin") version "1.0.0"
}

// Minimal setup - everything is auto-detected
fluxzero {
    projectFiles {
        enabled.set(true)
    }
}

// Or with explicit configuration
fluxzero {
    projectFiles {
        // Keep the plugin configured but skip all syncing when false (default: true).
        enabled.set(true)

        // Sync once from the root project in multi-module builds (default: true).
        rootProjectOnly.set(true)

        // Re-download and rewrite files even when local sync metadata is current (default: false).
        forceUpdate.set(false)

        // Use only when language detection is wrong or unavailable. Values: "kotlin" or "java".
        overrideLanguage.set("kotlin")

        // Use only when the SDK version cannot be inferred from dependencies, BOMs, or properties.
        overrideSdkVersion.set("1.2.0")
    }
}
```

**settings.gradle.kts**
```kotlin
pluginManagement {
    repositories {
        mavenCentral()
    }
}
```

**Command line properties:**
```bash
./gradlew build -Pfluxzero.projectFiles.enabled=false
./gradlew build -Pfluxzero.projectFiles.overrideLanguage=kotlin
./gradlew syncProjectFiles -Pfluxzero.projectFiles.forceUpdate=true
```

### Maven Plugin

**pom.xml**
```xml
<build>
    <plugins>
        <plugin>
            <groupId>io.fluxzero.tools</groupId>
            <artifactId>fluxzero-maven-plugin</artifactId>
            <version>1.0.0</version>
            <executions>
                <execution>
                    <goals>
                        <goal>sync-project-files</goal>
                    </goals>
                </execution>
            </executions>
        </plugin>
    </plugins>
</build>
```

**With explicit configuration:**
```xml
<plugin>
    <groupId>io.fluxzero.tools</groupId>
    <artifactId>fluxzero-maven-plugin</artifactId>
    <version>1.0.0</version>
    <configuration>
        <!-- Keep the plugin configured but skip all syncing when false (default: true). -->
        <enabled>true</enabled>

        <!-- Sync once from the execution root in multi-module builds (default: true). -->
        <rootProjectOnly>true</rootProjectOnly>

        <!-- Re-download and rewrite files even when local sync metadata is current (default: false). -->
        <forceUpdate>false</forceUpdate>

        <!-- Use only when language detection is wrong or unavailable. Values: "kotlin" or "java". -->
        <overrideLanguage>kotlin</overrideLanguage>

        <!-- Use only when the SDK version cannot be inferred from dependencies, BOMs, or properties. -->
        <overrideSdkVersion>1.75.1</overrideSdkVersion>
    </configuration>
    <executions>
        <execution>
            <goals>
                <goal>sync-project-files</goal>
            </goals>
        </execution>
    </executions>
</plugin>
```

**Command line properties:**
```bash
mvn clean install -Dfluxzero.projectFiles.enabled=false
mvn clean install -Dfluxzero.projectFiles.overrideLanguage=kotlin
mvn clean install -Dfluxzero.projectFiles.forceUpdate=true
```

### Plugin Documentation

For detailed documentation, troubleshooting, and advanced examples:
- [Gradle Plugin README](gradle-plugin/README.md)
- [Maven Plugin README](maven-plugin/README.md)

## Development

### Building the CLI

```bash
./gradlew build shadowJar
```

### Building Native Executable

Requires GraalVM with native-image support:

```bash
# Switch to GraalVM (example using jenv)
jenv local oracle64-21.0.1

# Build native executable
./gradlew nativeCompile

# Test the native executable
./build/native/nativeCompile/flux version
```

### Running

```bash
./gradlew run
# or
java -jar build/libs/fluxzero-cli-dev-all.jar version
```

### Testing

```bash
./gradlew test
```

## Architecture

- **Kotlin-based** with Clikt for command-line parsing and JLine for interactive prompts
- **Template system** with ZIP-based project scaffolding and YAML-based customization
- **Multi-platform builds** via GitHub Actions for Linux, macOS, and Windows (ARM64/x86_64)
- **Native image compilation** using GraalVM for optimal performance
- **Automated installation** with platform detection and PATH integration
- **Per-project versioning** via `.flux/config.yaml`

## CI/CD

- **Native Build Workflow**: Builds native executables for Linux, macOS, and Windows with integration testing
- **Release Workflow**: Auto-versioning with git tags and comprehensive artifact releases
- **Automated testing**: 
  - Unit tests with MockK
  - Integration tests (CLI functionality + project generation)
  - Installation script verification
  - Cross-platform build verification
- **Release Artifacts**:
  - Native executables for all platforms
  - JAR for cross-platform compatibility
  - Installation and uninstallation scripts

## License

This project is licensed under the MIT License - see the LICENSE file for details.

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.
