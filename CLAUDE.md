# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

fluxzero-cli is a Kotlin-based command-line tool for interacting with Flux and Flux Cloud. It provides scaffolding capabilities for new projects, dependency upgrades, example code generation, and Flux cloud resource management. The CLI is distributed as a JAR and uses per-project versioning via `.flux/config.yaml`.

## Development Commands

### Building and Running
- `./gradlew build` - Builds the project
- `./gradlew shadowJar` - Builds the fat JAR with all dependencies
- `./gradlew test` - Runs all tests using JUnit Platform
- `./gradlew run` - Runs the application directly
- `./gradlew runShadow` - Runs using the shadow JAR

### Testing and Quality
- `./gradlew test` - Unit tests (uses JUnit with MockK for mocking)
- Static analysis is configured via Detekt (config in `config/detekt/detekt.yml`)
- Code style follows Kotlin conventions with line length limit of 180 characters

### Native Image Build
- `./gradlew nativeCompile` - Builds native executable using GraalVM
- Requires GraalVM with native-image tool installed (e.g., `jenv local oracle64-21.0.1`)
- Native executable provides ~5x faster startup time (~0.27s vs ~1.36s for JAR)
- Self-contained binary (no JVM required), but larger file size (~42MB vs ~7.5MB JAR)

### Template Management
- `./gradlew zipTemplates` - Archives template folders from `templates/` directory as ZIP files for distribution
- Templates are packaged into the JAR resources during build

## Architecture

### Core Components

**Main Application** (`src/main/kotlin/host/flux/cli/`):
- `Main.kt` - Entry point with update checking and command setup using Clikt framework
- Configuration loading from `~/.flux/cli.properties` and `.flux/cli.properties`

**Commands** (`commands/`):
- `Init.kt` - Project scaffolding with template selection and customization
- `Version.kt` - Version display and update notifications  
- `Upgrade.kt` - Dependency and CLI upgrades

**Core Services**:
- `UpdateChecker.kt` - Automatic version checking on startup
- `TemplateExtractor.kt` - Template processing and project generation
- `DefaultInstaller.kt` - CLI installation logic
- `JLinePrompt.kt` - Interactive user prompts using JLine

### Template System

Templates are stored in `templates/` directory and packaged as ZIP files during build:
- Each template is a complete working project
- `customise.yaml` files define template customization rules (package name replacement, file modifications)
- Templates support regex-based find/replace operations for customization
- Template selection is interactive during `init` command

### Build System

Uses Gradle with Kotlin DSL:
- Kotlin 2.1.20 with JVM target 21
- Shadow plugin for fat JAR creation
- GraalVM Native Build Tools plugin for native image compilation
- Custom `zipTemplates` task for template packaging
- Dependencies: Clikt (commands), JLine (prompts), MockK (testing)

### CI/CD Workflows

**Native Build Workflow** (`.github/workflows/native-build.yml`):
- Builds native executables for Linux (x86_64, ARM64) and macOS (Intel, Apple Silicon)
- Uses GitHub's native ARM64 runners and standard x86_64 runners
- Triggered on pushes to main/feature branches and PRs

**Release Workflow** (`.github/workflows/release.yml`):
- Integrates native build artifacts with JAR releases
- Auto-versioning with git tags
- Distributes multiple artifacts:
  - `fluxzero-cli.jar` (cross-platform JAR)
  - `flux-linux-amd64`, `flux-linux-arm64` (Linux native executables)
  - `flux-macos-amd64`, `flux-macos-arm64` (macOS native executables)
  - `install.sh` (installation script)

### Project Structure

```
src/main/kotlin/host/flux/cli/
├── Main.kt                  - Application entry point
├── UpdateChecker.kt         - Version checking
├── commands/                - CLI commands
├── install/                 - Installation logic  
├── prompt/                  - User interaction
└── template/               - Template processing

templates/                   - Project templates
├── flux-kotlin-single/     - Single module Kotlin template
└── [other templates]

config/detekt/              - Code quality configuration
```

### Key Dependencies

- **Clikt 5.0.3** - Command-line interface framework
- **JLine 3.30.4** - Interactive console input
- **MockK 1.14.2** - Mocking framework for tests
- **Detekt** - Static code analysis
- **GraalVM Native Build Tools 0.10.6** - Native image compilation

## Development Notes

- CLI uses per-project versioning (not global installation)
- Templates are full working examples that get customized during generation
- Update checking happens automatically on startup
- Interactive prompts handle template selection and project naming
- Package structure follows reverse domain naming: `host.flux.cli`

### GraalVM Configuration

Native image build is configured with:
- `--no-fallback` - Pure native mode (no JVM fallback)
- `--install-exit-handlers` - Proper shutdown handling
- `--enable-url-protocols=https` - HTTPS support for downloads
- `--report-unsupported-elements-at-runtime` - Runtime error reporting
- `--initialize-at-build-time=kotlin` - Kotlin classes initialized at build time
- `--initialize-at-run-time=org.jline` - JLine initialized at runtime (terminal compatibility)
- Resource autodetection enabled for templates and static resources