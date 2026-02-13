# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Changed

- **Gradle Plugin Publishing**: Removed publishing to Gradle Plugin Portal. The Gradle plugin is now only published to Maven Central as `io.fluxzero.tools:fluxzero-gradle-plugin`.
- **Gradle Plugin ID**: Changed plugin ID from `io.fluxzero.tools.gradle` to `io.fluxzero.tools.gradle.plugin`. The main artifact coordinates remain `io.fluxzero.tools:fluxzero-gradle-plugin`.

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
