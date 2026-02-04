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
 *     projectFiles {
 *         enabled.set(true)
 *         overrideLanguage.set("kotlin")  // or "java", or leave empty for auto-detection
 *         overrideSdkVersion.set("1.0.0") // optional, auto-detected from dependencies
 *         forceUpdate.set(false)  // set to true to force re-download
 *     }
 *
 *     // Future features can be added here
 * }
 * ```
 */
abstract class FluxzeroExtension @Inject constructor(objects: ObjectFactory) {

    /**
     * Configuration for the project files sync feature.
     */
    val projectFiles: ProjectFilesExtension = objects.newInstance(ProjectFilesExtension::class.java)

    /**
     * Configures the project files sync feature.
     */
    fun projectFiles(action: Action<ProjectFilesExtension>) {
        action.execute(projectFiles)
    }
}
