package host.flux.gradle

import org.gradle.api.provider.Property

/**
 * Configuration for the agent files sync feature.
 *
 * This feature automatically syncs AI assistant instruction files
 * (AGENTS.md, CLAUDE.md, .aiassistant/, .junie/) from GitHub releases.
 *
 * By default, the SDK version and language are auto-detected from your project.
 * Use the override properties only when auto-detection doesn't work correctly.
 */
abstract class AgentFilesExtension {
    /**
     * Whether agent file syncing is enabled.
     * Defaults to true.
     */
    abstract val enabled: Property<Boolean>

    /**
     * Override the auto-detected language ("kotlin" or "java").
     * Only set this if auto-detection fails or returns the wrong language.
     */
    abstract val overrideLanguage: Property<String>

    /**
     * Override the auto-detected SDK version.
     * Only set this if auto-detection fails or you need a specific version.
     */
    abstract val overrideSdkVersion: Property<String>

    /**
     * Whether to force re-download of agent files even if they already exist.
     * Defaults to false.
     */
    abstract val forceUpdate: Property<Boolean>

    /**
     * Whether to only run on the root project in a multi-module build.
     * When true (default), subprojects will skip agent files sync.
     * Set to false to run on every project.
     */
    abstract val rootProjectOnly: Property<Boolean>
}
