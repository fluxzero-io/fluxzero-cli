package host.flux.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPlugin
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType

/**
 * Gradle plugin that automatically syncs AI agent files for Fluxzero projects.
 *
 * When applied to a project, this plugin:
 * 1. Creates a `fluxAgents` extension for configuration
 * 2. Registers a `syncAgentFiles` task
 * 3. Hooks the task into the build lifecycle (runs before compilation)
 *
 * The plugin uses Gradle's incremental build support to avoid unnecessary downloads.
 * When the SDK version (detected from dependencies) changes, agent files are updated.
 *
 * Usage in build.gradle.kts:
 * ```kotlin
 * plugins {
 *     id("io.fluxzero.agents") version "1.0.0"
 * }
 *
 * fluxAgents {
 *     // Optional configuration
 *     language.set("kotlin")  // auto-detected if not specified
 *     enabled.set(true)       // disable with false
 * }
 * ```
 */
class FluxAgentsPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        // Create the extension
        val extension = project.extensions.create<FluxAgentsExtension>("fluxAgents")

        // Set default values
        extension.enabled.convention(true)
        extension.forceUpdate.convention(false)

        // Register the sync task
        val syncTask = project.tasks.register<SyncAgentFilesTask>("syncAgentFiles") {
            // Configure task only if enabled
            onlyIf { extension.enabled.get() }

            // Set up project directory
            projectDir.set(project.layout.projectDirectory)

            // Set up output directory for tracking changes
            agentFilesDir.set(project.layout.projectDirectory.dir(".aiassistant"))

            // Configure version - use extension value or auto-detect
            sdkVersion.set(project.provider {
                extension.sdkVersion.orNull
                    ?: SyncAgentFilesTask.detectSdkVersion(project.projectDir)
            })

            // Configure language - use extension value or auto-detect
            language.set(project.provider {
                extension.language.orNull
                    ?: SyncAgentFilesTask.detectLanguage(project.projectDir)
            })

            // Configure force update
            forceUpdate.set(extension.forceUpdate)
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
            if (extension.enabled.get()) {
                val version = extension.sdkVersion.orNull
                    ?: SyncAgentFilesTask.detectSdkVersion(project.projectDir)
                val lang = extension.language.orNull
                    ?: SyncAgentFilesTask.detectLanguage(project.projectDir)

                if (version == "unknown") {
                    project.logger.info(
                        "Fluxzero Agents plugin: No SDK version detected. " +
                            "Agent files will not be synced unless sdk version is manually configured."
                    )
                } else {
                    project.logger.info(
                        "Fluxzero Agents plugin configured: version=$version, language=$lang"
                    )
                }
            } else {
                project.logger.info("Fluxzero Agents plugin is disabled")
            }
        }
    }
}
