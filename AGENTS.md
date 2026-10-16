# AGENTS.md

This file provides guidance to Codex (Codex.ai/code) when working with code in this repository.

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
- Use the native launcher configured in `cli/build.gradle.kts` and keep it aligned with `.github/workflows/native-build.yml`
- Native executable provides ~5x faster startup time (~0.27s vs ~1.36s for JAR)
- Self-contained binary (no JVM required), but larger file size (~42MB vs ~7.5MB JAR)

### Template Management
- `./gradlew :templates:packageTemplates` - Packages the embedded Java and Kotlin template sources as CLI resources
- `./gradlew :templates:test` - Verifies template packaging, version injection, and refactoring behavior
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

Templates are stored in `templates/src/main/template-sources/` and packaged as ZIP files during build:
- Each template is a complete working project
- `refactor.yaml` files define template customization rules (package name replacement, file modifications)
- Templates support regex-based find/replace operations for customization
- Template selection is interactive during `init` command
- Release builds inject the same version into the embedded Maven and Gradle plugin configuration

### Build System

Uses Gradle with Kotlin DSL:
- Root `build.gradle.kts` owns Kotlin/plugin versions and the JVM compatibility floor
- Shadow plugin for fat JAR creation
- GraalVM Native Build Tools plugin for native image compilation
- Custom `:templates:packageTemplates` task for deterministic, cross-platform template packaging
- Dependencies: Clikt (commands), JLine (prompts), MockK (testing)

### CI/CD Workflows

**Native Build Workflow** (`.github/workflows/native-build.yml`):
- Reusable qualification for Linux x86_64, macOS Intel/Apple Silicon and Windows x86_64
- Called by release and applicable PR jobs; inspect the caller's target matrix before assuming platform coverage
- Exercises real executable startup, project generation, development lifecycle and installation

**Release Workflow** (`.github/workflows/release.yml`):
- Sequential pipeline: build-and-test → native-build → release
- Prevents expensive native builds from running on failing code
- Auto-versioning with git tags
- Distributes multiple artifacts:
  - `fluxzero-cli.jar` (cross-platform JAR)
  - `flux-linux-amd64` (Linux native executable)
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

templates/src/main/template-sources/
├── flux-basic-java/         - Java starter with Maven and Gradle support
└── flux-basic-kotlin/       - Kotlin starter with Maven and Gradle support

config/detekt/              - Code quality configuration
```

### Key Dependencies

- **Clikt** - Command-line interface framework
- **JLine** - Interactive console input
- **MockK** - Mocking framework for tests
- **Detekt** - Static code analysis
- **GraalVM Native Build Tools** - Native image compilation

## Development Notes

- CLI uses per-project versioning (not global installation)
- Templates are full working examples that get customized during generation
- Update checking happens automatically on startup
- Interactive prompts handle template selection and project naming
- Package structure follows reverse domain naming: `host.flux.cli`

## Commit Messages

- Use Conventional Commits with a clear domain scope for human-authored commits.
- Format: `<type>(<domain>): <short imperative summary>`.
- Good examples: `fix(maven-plugin): describe plugin parameters`, `feat(init): add template selection flag`, `test(project-files): cover BOM version detection`.
- Common types: `feat`, `fix`, `refactor`, `test`, `docs`, `build`, `ci`, `deps`, `perf`, and `chore`.
- Useful domains in this repo include `cli`, `init`, `upgrade`, `templates`, `project-files`, `gradle-plugin`, `maven-plugin`, `api`, `native`, `release`, `ci`, `deps`, and `docs`.
- For non-trivial commits, include a body that explains why the change is needed, the behavioral impact, and the tests that were run.

### GraalVM Configuration

Native image build is configured with:
- `--no-fallback` - Pure native mode (no JVM fallback)
- `--install-exit-handlers` - Proper shutdown handling
- `--enable-url-protocols=https` - HTTPS support for downloads
- `--report-unsupported-elements-at-runtime` - Runtime error reporting
- `--initialize-at-build-time=kotlin` - Kotlin classes initialized at build time
- `--initialize-at-run-time=org.jline` - JLine initialized at runtime (terminal compatibility)
- Resource autodetection enabled for templates and static resources

## Dependency Maintenance Boundaries

- `renovate.json` owns dependency discovery and supported-version boundaries. The injected starter versions are in `templates/template-versions.properties`; public Fluxzero lookups use Packages rather than Central alone.
- GitHub dependency alerts and automatic Gradle dependency submission are separate from Renovate update generation. Inspect a real submitted graph and the hosted updater results; an empty alert list alone is insufficient.
- Keep the provided Maven API floor in `maven-plugin/build.gradle.kts` and `maven-plugin/pom.xml` aligned. Those interfaces are supplied by the consuming Maven runtime. Updating them can silently raise the generated descriptor's minimum Maven version; current test/runtime tooling can advance separately.
- Run the existing owning publication and embedded-template scripts when changing build plugins or injected versions. Preserve signed main, sources, Javadoc and Gradle marker artifacts and verify independent consumers.
- The required PR gates include applicable native builds and actual executable-update regressions. Unix native qualification also exercises JLine through a real pseudo-terminal; a noninteractive version command does not cover that path.
- WinGetCreate has a central source pin in `config/publishing-tools.properties`; its published target framework determines the CI runtime. Qualify the executable with `scripts/prepare-winget-create.ps1` before changing that runtime.
