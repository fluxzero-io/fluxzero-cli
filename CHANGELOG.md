# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- **Agent Files Auto-Update System**: New modules for automatically syncing AI assistant files (AGENTS.md, CLAUDE.md, .aiassistant/, .junie/) in Fluxzero projects.

  - `agents-core` module: Shared core library with:
    - `GitHubReleaseClient` - Fetches agent files from GitHub release assets
    - `LanguageDetector` - Auto-detects project language (Kotlin/Java) from source files and build configuration
    - `SdkVersionDetector` - Extracts Fluxzero SDK version from build files (Gradle, Maven, version catalogs)
    - `AgentFilesExtractor` - Safely extracts ZIP archives to project directories
    - `AgentFilesService` - Main orchestration service for syncing agent files

  - `gradle-plugin` module: Gradle plugin (`io.fluxzero.agents`) that:
    - Automatically syncs agent files when SDK version changes
    - Integrates with Gradle's incremental build system (UP-TO-DATE when no changes)
    - Hooks into compilation lifecycle (runs before compileJava/compileKotlin)
    - Provides `fluxAgents` extension for configuration
    - Supports language override, force update, and disable options

  - `maven-plugin` module: Maven plugin (`fluxzero-agents-maven-plugin`) that:
    - Provides `sync-agents` goal for syncing agent files
    - Runs in INITIALIZE phase by default
    - Supports same configuration options as Gradle plugin

### Architecture

The system fetches agent files from GitHub release assets (`agent-files-kotlin.zip`, `agent-files-java.zip`) published with SDK releases. This ensures agent files stay in sync with the SDK version being used in the project.
