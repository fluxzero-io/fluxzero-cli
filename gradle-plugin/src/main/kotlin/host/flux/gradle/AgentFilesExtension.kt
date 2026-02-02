package host.flux.gradle

import org.gradle.api.provider.Property

/**
 * Configuration for the agent files sync feature.
 *
 * This feature automatically syncs AI assistant instruction files
 * (AGENTS.md, CLAUDE.md, .aiassistant/, .junie/) from GitHub releases.
 */
abstract class AgentFilesExtension {
    /**
     * Whether agent file syncing is enabled.
     * Defaults to true.
     */
    abstract val enabled: Property<Boolean>

    /**
     * The language of the project ("kotlin" or "java").
     * If not specified, auto-detected based on source files and build configuration.
     */
    abstract val language: Property<String>

    /**
     * Override the SDK version to use for fetching agent files.
     * If not specified, detected from project dependencies.
     */
    abstract val sdkVersion: Property<String>

    /**
     * Whether to force re-download of agent files even if they already exist.
     * Defaults to false.
     */
    abstract val forceUpdate: Property<Boolean>
}
