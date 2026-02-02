package host.flux.gradle

import org.gradle.api.provider.Property

/**
 * Extension for configuring the Fluxzero Gradle plugin.
 *
 * Example usage in build.gradle.kts:
 * ```kotlin
 * fluxzero {
 *     language.set("kotlin")  // or "java", or leave empty for auto-detection
 *     enabled.set(true)       // can be set to false to disable the plugin
 *     forceUpdate.set(false)  // set to true to force re-download
 * }
 * ```
 */
abstract class FluxzeroExtension {
    /**
     * The language of the project ("kotlin" or "java").
     * If not specified, the plugin will auto-detect based on source files and build configuration.
     */
    abstract val language: Property<String>

    /**
     * Whether the plugin is enabled.
     * Defaults to true. Set to false to disable agent file syncing.
     */
    abstract val enabled: Property<Boolean>

    /**
     * Whether to force re-download of agent files even if they already exist.
     * Defaults to false. Set to true to always fetch the latest files.
     */
    abstract val forceUpdate: Property<Boolean>

    /**
     * Override the SDK version to use for fetching agent files.
     * If not specified, the plugin will detect the version from project dependencies.
     */
    abstract val sdkVersion: Property<String>
}
