# fluxzero-cli 

A command-line interface for [Flux](https://fluxcapacitor.io/) that helps you scaffold new projects, perform dependency upgrades, generate example code, and manage Flux Cloud resources.

## Installation

### Option 1: Package Managers (Recommended)

**macOS or Linux with Homebrew:**
```bash
brew install fluxzero-io/tap/fluxzero
```

**Windows with WinGet:**
```powershell
winget install --exact --id Fluxzero.FluxzeroCLI
```

Package-manager installations receive normal `brew upgrade` and `winget upgrade` support.

### Option 2: Automated Installation

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

### Option 3: Manual Native Executable Download

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

### Option 4: Fluxzero Launchpad

Fluxzero also ships a macOS app, Fluxzero Launchpad, for generating projects locally and opening them in coding agents.

The signed and notarized macOS DMG is published with each release:

- **macOS Intel / Apple Silicon**: [`Fluxzero-Launchpad.dmg`](https://github.com/fluxzero-io/fluxzero-cli/releases/latest/download/Fluxzero-Launchpad.dmg)

Windows and Linux Launchpad apps are planned, but are not published yet.

The app manages its own `fz` binary, checks for the latest CLI release on launch, writes a `START_PROMPT.md` containing
the user's project brief, opens local coding agents with the generated project path and prompt, and keeps a local history
of generated projects. Installed macOS builds register experimental Fluxzero URL schemes:

- `fluxzero://new?...` opens Launchpad and pre-fills the generator.
- `fluxzero://open?path=...&prompt=...&agent=codex|claude|cursor|finder|none` opens an existing project directly.
- `fluxzero://create?name=...&prompt=...&agent=codex|claude|cursor|finder|none` creates a project with defaults and opens it directly.

### Option 5: Manual JAR Installation (Legacy)

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

Once installed, you can use the CLI with either the short `fz` command or the equivalent `fluxzero` command:

```bash
# Initialize a new project (interactive template selection)
fz init my-project

# List available templates
fz templates list

# Start the local Fluxzero development environment
fz dev

# Expose the active environment to an MCP client over stdio
fz mcp

# Show version
fz version

# Upgrade CLI to latest version
fz upgrade
```

### Local development

`fz dev` (or `fluxzero dev`) starts the newest compatible stable Fluxzero dev server. It supervises the local runtime,
proxy, IDP, application reloads, background tests, optional frontend process, diagnostics, and MCP endpoint. Ports and
credentials are allocated and discovered automatically through `.fluxzero/dev/session.json`.

The application main class is detected from compiled Java or Kotlin classes. Use `--main-class` only when a project
contains multiple executable entrypoints and the intended one is ambiguous.

```bash
fz dev
```

The default command starts the environment independently from the terminal and attaches a live semantic event view.
Type `q` or `quit` and press Enter to open a menu, use the arrow keys to select an action, and press Enter to confirm.
Type `d` or `detach` and press Enter to leave it running. `Ctrl-C` and an unexpected terminal disconnect stop the
environment and all applications; background ownership is therefore always an explicit choice. When no Maven or
Gradle project exists in the selected folder, interactive use offers to create a project in that folder or a new
subfolder before startup. Common overrides include:

```bash
fz dev --fast-compiler
fz dev --app app
fz dev --app app --app audittrail
fz dev --environment dev
fz dev --port 4200
fz dev --idp external
fz dev --frontend-command "npm run dev"
fz dev --frontend-directory frontend --frontend-setup-command "npm install --prefer-offline --no-audit --no-fund"
fz dev --frontend-url http://localhost:5173
fz dev --no-tests
```

Attach to an existing project environment with `fz dev attach`; a bare `fz dev` does the same when the environment is
already running. Events produced while detached are replayed from the last attach cursor before live events resume.
Use background mode to start and return immediately after readiness without opening the attached view:

```bash
fz dev --background
fz dev list
fz dev list --json
fz dev attach
fz dev status
fz dev status --json
fz dev logs --follow
fz dev logs --follow --errors
fz dev logs --follow --app orders
fz dev stop
fz dev stop --force
fz dev stop --all
fz dev config
```

`fz dev list` is global: run it from any directory to see every known Fluxzero development environment, including its
project, applications, browser URL, and whether it is running, unresponsive, or stale. Project-specific control
commands still use the current project or `--project-dir`.
`fz dev stop --all` stops every registered environment and removes stale or legacy macOS launchd registrations.

`logs --follow` closes automatically when the environment stops, so it is safe to use as a long-running agent command.
`fz dev config` prints the complete, valid `.fluxzero/dev.yaml` reference owned by the current compatible dev-server
version. This gives humans and coding agents the exact supported field names, nesting, and frontend routing options
without requiring a repository checkout or separate documentation lookup.

Only one dev session may be active per project. `status`, `logs`, `stop`, MCP discovery, and the next `dev` launch
reconcile stale session state when the supervisor was killed unexpectedly. Because that also means the embedded test
runtime lost its in-memory data, startup commands run again in the next session. Detaching keeps that state alive when a
terminal closes, but also keeps the environment's processes and memory in use until `fz dev stop` or its configured
idle timeout.

Start options:

| Option | Meaning |
|--------|---------|
| `--app <selector>` | Start one module, main class, test app, or named app configuration; repeatable. |
| `--main-class <class>` | Override main-class detection. |
| `--application-name <name>` | Override the Fluxzero runtime application name. |
| `--environment <name>` | Set `ENVIRONMENT`; defaults to `local`. |
| `--namespace <name>` | Set the Fluxzero namespace. |
| `--port <port>` | Prefer a public browser/gateway port; dynamic by default. |
| `--idp managed|external` | Start the local IDP or use application-owned IDP configuration. |
| `--no-idp` | Alias for `--idp external`. |
| `--frontend-command <command>` | Start a managed frontend; use `{port}` for its private upstream port. |
| `--frontend-directory <path>` | Working directory for managed frontend commands. |
| `--frontend-setup-command <command>` | Run setup once before the managed frontend starts for this dev session. |
| `--frontend-url <url>` | Proxy an externally managed frontend. |
| `--no-frontend` | Run a backend-only environment. |
| `--backend-path <path>` | Route an extra public path directly to Fluxzero; repeatable. |
| `--fast-compiler` | Enable the Maven-correct fast Java path; Maven remains the fallback. |
| `--no-tests` | Disable background test selection and execution. |
| `--no-watch` | Disable source watching. |
| `--no-compile-on-start` | Start infrastructure without compiling applications. |
| `--app-arg <argument>` | Pass an application argument; repeatable. |
| `--startup-timeout-ms <ms>` | Override application/frontend readiness timeout. |
| `--graceful-shutdown-timeout-ms <ms>` | Override rolling app shutdown timeout. |
| `--debounce-ms <ms>` | Override source-change debounce. |
| `--idle-timeout <duration>` | Stop a ready inactive environment; defaults to `8h`, use `disabled` to opt out. |
| `--failed-startup-timeout <duration>` | Stop an unready inactive environment; defaults to `10m`. |
| `--background`, `--detach`, `-d` | Start without an attached live view and return after startup succeeds or fails. |

Shared project defaults belong in the tracked `.fluxzero/dev.yaml`; session state, logs, tokens, and build snapshots
remain ignored under `.fluxzero/dev/`:

```yaml
version: 1
environment: local
apps:
  - app
port: 4200
idp: external
frontend:
  command: "cd frontend && npm start -- --host 127.0.0.1 --port {port}"
  backendPaths:
    - /api
lifecycle:
  idleTimeout: 8h
  failedStartupTimeout: 10m
applicationConfig:
  rebound-encrypted:
    application: rebound
    applicationName: rebound
    env:
      FEATURE_MODE: local
    secrets:
      ENCRYPTION_KEY: "op://Fluxzero Cloud/flux_cloud_flux-encryption-key/local encryption-key"
commands:
  create-admin:
    type: com.example.CreateUser
    payload:
      name: Local Admin
```

`apps` may contain direct selectors or keys from `applicationConfig`. Secret values never belong in this file: only
tracked `op://` references are allowed, and `op run` injects their values directly into the selected child process.
YAML commands execute in declaration order, followed by JSON commands under
`src/test/resources/fluxzero/dev/commands` in filename order.

Command-line options override environment variables, which override `dev.yaml`; built-in defaults apply last.
Unknown keys and unsupported config versions fail startup instead of being silently ignored.

`fz mcp` or `fluxzero mcp` is intended as the stdio command in an agent's MCP configuration. It discovers the active environment from
the project directory and reads the dynamic endpoint and token without exposing either in agent configuration. Agent plugins can
use `--ensure-dev` to start exactly one background environment when needed; an already active project session is reused:

```bash
fz mcp --ensure-dev --project-dir /path/to/project
```

Once connected, the dev environment owns source watching, compilation, application replacement, configured startup commands, and
background test execution. Coding agents should consume its structured MCP feedback rather than start duplicate builds, tests,
applications, watchers, or unbounded log followers.

`fz dev` resolves the newest stable dev-server `1.x` release for a new environment. The verified standalone JAR is
cached under `~/.fluxzero/cache/dev-server`, while `.fluxzero/dev/launcher` pins the concrete version used by the
project. Attach, status, logs, stop, and MCP commands keep using that pinned version. Set
`FLUXZERO_DEV_SERVER_VERSION` or pass `--dev-server-version` only when testing a specific local or prerelease build.

Project-local launchers provide the same environment without a globally installed CLI:

```bash
./mvnw fluxzero:dev
./mvnw fluxzero:dev -Dfluxzero.dev.background=true

./gradlew fluxzeroDev
./gradlew fluxzeroDev -Pfluxzero.dev.background=true
```

The Gradle plugin also owns `fluxzeroDevMetadata`, the compile/classpath contract consumed by the dev server. Apply the
plugin to the root project so multi-project applications are discovered together.

### CLI Commands & Parameters

#### `fz init` - Initialize a new project

**Basic usage:**
```bash
fz init [OPTIONS]
```

**Options:**

| Parameter | Description | Example |
|-----------|-------------|---------|
| `--template` | Name of the template to use | `--template flux-basic-kotlin` |
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

**`fz init` examples:**

```bash
# Interactive mode (prompts for all options)
fz init

# With built-in template
fz init --template flux-basic-kotlin --name my-app --package com.example.myapp --build gradle

# Using custom template directory
fz init --template-path ./my-templates --template custom-template --name my-project

# Using custom template ZIP file
fz init --template-path ./templates/my-template.zip --template my-template --name my-project

# Full example with all options
fz init \
  --template flux-basic-java \
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

- **Native executables**: Self-contained for regular CLI commands
- **Development server**: Java 25 or higher available through `JAVA_HOME` or `PATH`
- **JAR version**: Java 21 or higher for regular CLI commands; `fz dev` has the development-server requirement above

## Templates

Template sources live in `templates/src/main/template-sources` and are versioned, tested, and released atomically with
the CLI and its Maven and Gradle plugins. The `:templates:packageTemplates` task creates the embedded ZIP resources and
injects the current release version into each generated project's plugin configuration. Development builds use the
configured released fallback plugin version by default; pass `-PtemplatePluginVersion=<version>` when validating a
different locally published version.

**Template features:**
- Package name replacement
- File removal based on configuration
- Line-by-line content modification
- File permission management
- Interactive customization during project creation

Available templates:
- `flux-basic-java` - Java starter with Maven and Gradle wrappers
- `flux-basic-kotlin` - Kotlin starter with Maven and Gradle wrappers

These are generic starters. Generated code must still be adapted to the actual product requirements. The templates do
not contain local AI-agent manuals; current Fluxzero guidance is distributed by the separately installable Fluxzero
Codex plugin and its MCP server. Existing projects may still opt into local instruction files through the supported
Gradle plugin or Maven `sync-project-files` goal described below.

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

Build the embedded template resources directly with `./gradlew :templates:packageTemplates`. They are written to
`templates/build/generated/resources/templates` and are included automatically in CLI JARs and native executables.

## Build Plugins

Fluxzero provides Gradle and Maven plugins that run the local development environment, publish layered Java OCI
packages, and optionally synchronize AI agent instruction files.

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
    packagePublishing {
        packageName.set("my-service")
        images.add("registry.fluxzero.io/\${organisationId}/\${packageName}")
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
./gradlew build fluxzeroPublishPackage
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
./gradlew build :cli:shadowJar
```

### Building Native Executable

Requires GraalVM with native-image support:

```bash
# Switch to GraalVM (example using jenv)
jenv local oracle64-21.0.1

# Build native executable
./gradlew :cli:nativeCompile

# Test the native executable
./cli/build/native/nativeCompile/flux version
```

### Running

```bash
./gradlew run
# or
java -jar cli/build/libs/fluxzero-cli-dev.jar version
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
- **Package-manager publishing**: Opens tested Homebrew formula updates and submits WinGet manifests from immutable release assets without rebuilding the CLI
- **Automated testing**: 
  - Unit tests with MockK
  - Integration tests (CLI functionality + project generation)
  - Installation script verification
  - Cross-platform build verification
- **Release Artifacts**:
  - Native executables for all platforms
  - WinGet-compatible portable Windows archive
  - JAR for cross-platform compatibility
  - Installation and uninstallation scripts

Package-manager publishing requires the organization secrets `FLUXZERO_BOT_APP_ID` and
`FLUXZERO_BOT_PRIVATE_KEY` for the `fluxzero-io/homebrew-tap` repository, plus a
`WINGET_CREATE_GITHUB_TOKEN` classic PAT with `public_repo` scope from the account that has accepted
Microsoft's contributor license agreement.

## License

The CLI and build plugins are licensed under the EUPL-1.2; see `LICENSE`. The embedded starter-template sources under
`templates/src/main/template-sources` retain their Apache-2.0 license; see `templates/LICENSE`.

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.
