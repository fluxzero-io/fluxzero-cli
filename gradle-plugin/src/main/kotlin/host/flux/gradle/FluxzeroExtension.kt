package host.flux.gradle

import org.gradle.api.Action
import org.gradle.api.model.ObjectFactory
import javax.inject.Inject

/**
 * Extension for configuring the Fluxzero Gradle plugin.
 *
 * Example usage in build.gradle.kts:
 * ```kotlin
 * fluxzero {
 *     agentFiles {
 *         enabled.set(true)
 *         language.set("kotlin")  // or "java", or leave empty for auto-detection
 *         sdkVersion.set("1.0.0") // optional, auto-detected from dependencies
 *         forceUpdate.set(false)  // set to true to force re-download
 *     }
 *
 *     // Future features can be added here
 * }
 * ```
 */
abstract class FluxzeroExtension @Inject constructor(objects: ObjectFactory) {

    /**
     * Configuration for the agent files sync feature.
     */
    val agentFiles: AgentFilesExtension = objects.newInstance(AgentFilesExtension::class.java)

    /**
     * Configures the agent files sync feature.
     */
    fun agentFiles(action: Action<AgentFilesExtension>) {
        action.execute(agentFiles)
    }
}
