package host.flux.gradle

import org.gradle.api.provider.Property

/**
 * Configuration for the project files sync feature.
 *
 * This feature automatically syncs AI assistant instruction files
 * (AGENTS.md, CLAUDE.md, .aiassistant/, .junie/) from GitHub releases.
 *
 * By default, the SDK version and language are auto-detected from your project.
 * Use the override properties only when auto-detection doesn't work correctly.
 *
 * Each property can also be overridden from the command line with a
 * `fluxzero.projectFiles.*` Gradle project property.
 */
abstract class ProjectFilesExtension {
    /**
     * Whether project file syncing is enabled.
     * Defaults to true.
     * Command-line property: fluxzero.projectFiles.enabled.
     */
    abstract val enabled: Property<Boolean>

    /**
     * Override the auto-detected language ("kotlin" or "java").
     * Only set this if auto-detection fails or returns the wrong language.
     * Command-line property: fluxzero.projectFiles.overrideLanguage.
     */
    abstract val overrideLanguage: Property<String>

    /**
     * Override the auto-detected SDK version.
     * Only set this if auto-detection fails or you need a specific version.
     * Command-line property: fluxzero.projectFiles.overrideSdkVersion.
     */
    abstract val overrideSdkVersion: Property<String>

    /**
     * Whether to force re-download of project files even if they already exist.
     * Defaults to false.
     * Command-line property: fluxzero.projectFiles.forceUpdate.
     */
    abstract val forceUpdate: Property<Boolean>

    /**
     * Whether to only run on the root project in a multi-module build.
     * When true (default), subprojects will skip project files sync.
     * Set to false to run on every project.
     * Command-line property: fluxzero.projectFiles.rootProjectOnly.
     */
    abstract val rootProjectOnly: Property<Boolean>
}
