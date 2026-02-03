package host.flux.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPlugin
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType

/**
 * Gradle plugin for Fluxzero projects.
 *
 * Features:
 * - **Agent Files Sync**: Automatically syncs AI assistant instruction files
 *
 * Usage in build.gradle.kts:
 * ```kotlin
 * plugins {
 *     id("io.fluxzero.tools.gradle") version "1.0.0"
 * }
 *
 * // Minimal config - everything is auto-detected:
 * fluxzero {
 *     agentFiles {
 *         enabled.set(true)  // default
 *     }
 * }
 *
 * // Or with overrides if auto-detection fails:
 * fluxzero {
 *     agentFiles {
 *         overrideLanguage.set("kotlin")
 *         overrideSdkVersion.set("1.0.0")
 *     }
 * }
 * ```
 */
class FluxzeroPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        // Create the main extension
        val extension = project.extensions.create<FluxzeroExtension>("fluxzero")

        // Set default values for agent files feature
        extension.agentFiles.enabled.convention(true)
        extension.agentFiles.forceUpdate.convention(false)
        extension.agentFiles.rootProjectOnly.convention(true)

        // Register agent files sync feature
        registerAgentFilesFeature(project, extension)
    }

    private fun registerAgentFilesFeature(project: Project, extension: FluxzeroExtension) {
        val agentFiles = extension.agentFiles

        // Register the sync task
        val syncTask = project.tasks.register<SyncAgentFilesTask>("syncAgentFiles") {
            // Configure task only if feature is enabled and (not rootProjectOnly or this is root project)
            onlyIf {
                agentFiles.enabled.get() &&
                    (!agentFiles.rootProjectOnly.get() || project == project.rootProject)
            }

            // Set up project directory
            projectDir.set(project.layout.projectDirectory)

            // Set up output directory for tracking changes
            agentFilesDir.set(project.layout.projectDirectory.dir(".fluxzero"))

            // Configure version - use override value or auto-detect
            sdkVersion.set(project.provider {
                agentFiles.overrideSdkVersion.orNull
                    ?: SyncAgentFilesTask.detectSdkVersion(project.projectDir)
            })

            // Configure language - use override value or auto-detect
            language.set(project.provider {
                agentFiles.overrideLanguage.orNull
                    ?: SyncAgentFilesTask.detectLanguage(project.projectDir)
            })

            // Configure force update
            forceUpdate.set(agentFiles.forceUpdate)
        }

        // Hook into the build lifecycle - run before compilation
        project.plugins.withType<JavaPlugin> {
            project.tasks.named("compileJava") {
                it.dependsOn(syncTask)
            }
        }

        // Also hook into Kotlin compilation if present
        project.pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
            project.tasks.named("compileKotlin") {
                it.dependsOn(syncTask)
            }
        }

        // Log configuration at the end of evaluation
        project.afterEvaluate {
            if (agentFiles.enabled.get()) {
                val version = agentFiles.overrideSdkVersion.orNull
                    ?: SyncAgentFilesTask.detectSdkVersion(project.projectDir)
                val lang = agentFiles.overrideLanguage.orNull
                    ?: SyncAgentFilesTask.detectLanguage(project.projectDir)

                if (version == "unknown") {
                    project.logger.info(
                        "Fluxzero agent files: No SDK version detected. " +
                            "Agent files will not be synced unless overrideSdkVersion is set."
                    )
                } else {
                    project.logger.info(
                        "Fluxzero agent files configured: version=$version, language=$lang"
                    )
                }
            } else {
                project.logger.info("Fluxzero agent files sync is disabled")
            }
        }
    }
}
