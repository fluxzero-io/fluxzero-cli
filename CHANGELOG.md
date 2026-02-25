# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Fixed

- **Native Build**: Fixed hidden directories (`.fluxzero/`, `.run/`, `.gitignore`, etc.) being silently stripped from templates in native image builds. The `upload-artifact@v4` action excludes hidden files by default since September 2024; the templates cache artifact now opts in with `include-hidden-files: true`. This caused the CLI native binary to produce scaffolded projects missing dotfiles, while the JAR and REST API worked correctly.
- **Upgrade**: The `fz upgrade` command now downloads to a temporary file and atomically moves it into place. Previously, a crash mid-download would leave a corrupted binary, bricking the CLI.
- **Native Build**: Fixed segfault in JLine's native terminal code by building native images with GraalVM for Java 21 LTS instead of Java 25. Java 24+ enforces restricted method access for `System.load()`, which JLine uses for native terminal initialization, causing crashes in GraalVM native images.
- **Templates**: Template refactoring no longer warns about binary files (e.g. `favicon.ico`) when glob patterns like `src/main/resources/**/*` match them.

### Improved

- **Native Build**: Added CI assertions to verify hidden directories are present in scaffolded projects, preventing silent regressions.
- **Maven & Gradle Plugins**: Project files are now only downloaded when the SDK version or language changes. A `.fluxzero/.sync-version` metadata file tracks the last synced state, eliminating redundant GitHub API calls on every build.

### Fixed

- **Maven & Gradle Plugins**: Builds no longer fail when there is no internet connection during project files sync. Network errors now result in a warning instead of a build failure.
- **Maven Plugin**: Published POM no longer declares `project-files` as a compile dependency. The internal `project-files` module is now shaded into the fat JAR and marked `compileOnly`, matching the pattern used by the Gradle plugin. This fixes `Could not find artifact io.fluxzero.tools:project-files` errors for users of the published plugin.

### Changed

- **Templates**: Templates are now downloaded from GitHub release artifacts (`templates.zip`) instead of source archives, ensuring consistent template packaging. The `EXAMPLES_BRANCH` configuration has been replaced with `EXAMPLES_RELEASE_TAG` (default: `latest`).
- **Gradle Plugin Publishing**: Removed publishing to Gradle Plugin Portal. The Gradle plugin is now only published to Maven Central as `io.fluxzero.tools:fluxzero-gradle-plugin`.
- **Gradle Plugin ID**: Changed plugin ID from `io.fluxzero.tools.gradle` to `io.fluxzero.tools.gradle.plugin`. The main artifact coordinates remain `io.fluxzero.tools:fluxzero-gradle-plugin`.
- **Gradle Plugin**: Embedded `project-files` module and its dependencies (kotlinx-serialization, kotlin-logging, slf4j) into the plugin JAR using shadow. The plugin is now self-contained with no transitive dependencies on internal modules.

### Added

- **Maven Central Publishing**: Automated publishing of Fluxzero plugins to Maven Central via GitHub Actions workflow using the Vanniktech Maven Publish Plugin:
  - `io.fluxzero.tools:fluxzero-maven-plugin` - Maven plugin
  - `io.fluxzero.tools:fluxzero-gradle-plugin` - Gradle plugin
- **Maven Plugin**: Added `enabled` parameter for consistent API with Gradle plugin. Replaces `skip` as the recommended way to disable the plugin, though `skip` is kept for backward compatibility. Both `enabled=false` and `skip=true` can be used to disable execution.
- **Maven Plugin**: Added basic test coverage with configuration validation tests to prevent regressions.
- **Documentation**: Added comprehensive README.md files for both Gradle and Maven plugins with:
  - Quick start guides
  - Complete configuration reference
  - Troubleshooting guides
  - Real-world examples
  - Command-line property usage

### Improved

- **Error Messages**: Enhanced error messages in `project-files` to provide actionable guidance when SDK detection fails:
  - Now includes specific code examples for Gradle (build.gradle.kts) and Maven (pom.xml)
  - Shows how to add Fluxzero SDK dependencies correctly
  - Explains version catalog usage
  - Provides override options as fallback
- **Logging**: SDK version detector now logs which files were checked during detection and provides detailed warnings when version is not found, making troubleshooting easier.

### Fixed

- Maven and Gradle plugins now only run `sync-project-files` on the root project in multi-module builds by default, reducing redundant executions and confusing output. Configurable via `rootProjectOnly` parameter (`fluxzero.projectFiles.rootProjectOnly` property in Maven).
- SDK version detection now recognizes `fluxzero-bom` BOM dependencies in Maven `dependencyManagement` section.
- Maven plugin now bundles all dependencies (shadow JAR) to avoid classpath issues at runtime.

### Changed

- The `sync-project-files` task now fails the build when no Fluxzero SDK dependency is found, instead of silently skipping. Use `overrideSdkVersion` to specify the version explicitly if needed.
- **Maven Plugin**: `skip` parameter is now deprecated in favor of `enabled` for consistency with Gradle plugin. The `skip` parameter will continue to work for backward compatibility.

### Added

- **Project Files Auto-Update System**: New modules for automatically syncing AI assistant files (AGENTS.md, CLAUDE.md, .aiassistant/, .junie/) in Fluxzero projects.

  - `project-files` module: Shared core library with:
    - `GitHubReleaseClient` - Fetches project files from GitHub release assets
    - `LanguageDetector` - Auto-detects project language (Kotlin/Java) from source files and build configuration
    - `SdkVersionDetector` - Extracts Fluxzero SDK version from build files (Gradle, Maven, version catalogs)
    - `ProjectFilesExtractor` - Safely extracts ZIP archives to project directories
    - `ProjectFilesService` - Main orchestration service for syncing project files

  - `gradle-plugin` module: Gradle plugin (`io.fluxzero.tools.gradle.plugin`) that:
    - Automatically syncs project files when SDK version changes
    - Integrates with Gradle's incremental build system (UP-TO-DATE when no changes)
    - Hooks into compilation lifecycle (runs before compileJava/compileKotlin)
    - Provides `fluxzero { projectFiles { ... } }` extension for configuration
    - Supports language override, force update, and disable options

  - `maven-plugin` module: Maven plugin (`io.fluxzero.tools:fluxzero-maven-plugin`) that:
    - Provides `fluxzero:sync-project-files` goal for syncing project files
    - Runs in INITIALIZE phase by default
    - Properties prefixed with `fluxzero.projectFiles.*` (e.g., `fluxzero.projectFiles.language`)

### Architecture

The system fetches project files from GitHub release assets (`project-files-kotlin.zip`, `project-files-java.zip`) published with SDK releases. This ensures project files stay in sync with the SDK version being used in the project.
