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
 * - **Project Files Sync**: Automatically syncs AI assistant instruction files
 *
 * Usage in build.gradle.kts:
 * ```kotlin
 * plugins {
 *     id("io.fluxzero.tools.gradle") version "1.0.0"
 * }
 *
 * // Minimal config - everything is auto-detected:
 * fluxzero {
 *     projectFiles {
 *         enabled.set(true)  // default
 *     }
 * }
 *
 * // Or with overrides if auto-detection fails:
 * fluxzero {
 *     projectFiles {
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

        // Set default values for project files feature
        extension.projectFiles.enabled.convention(true)
        extension.projectFiles.forceUpdate.convention(false)
        extension.projectFiles.rootProjectOnly.convention(true)

        // Register project files sync feature
        registerProjectFilesFeature(project, extension)
    }

    private fun registerProjectFilesFeature(project: Project, extension: FluxzeroExtension) {
        val projectFiles = extension.projectFiles

        // Register the sync task
        val syncTask = project.tasks.register<SyncProjectFilesTask>("syncProjectFiles") {
            // Configure task only if feature is enabled and (not rootProjectOnly or this is root project)
            onlyIf {
                projectFiles.enabled.get() &&
                    (!projectFiles.rootProjectOnly.get() || project == project.rootProject)
            }

            // Set up project directory
            projectDir.set(project.layout.projectDirectory)

            // Set up output directory for tracking changes
            projectFilesDir.set(project.layout.projectDirectory.dir(".fluxzero"))

            // Configure version - use override value or auto-detect
            sdkVersion.set(project.provider {
                projectFiles.overrideSdkVersion.orNull
                    ?: SyncProjectFilesTask.detectSdkVersion(project.projectDir)
            })

            // Configure language - use override value or auto-detect
            language.set(project.provider {
                projectFiles.overrideLanguage.orNull
                    ?: SyncProjectFilesTask.detectLanguage(project.projectDir)
            })

            // Configure force update
            forceUpdate.set(projectFiles.forceUpdate)
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
            if (projectFiles.enabled.get()) {
                val version = projectFiles.overrideSdkVersion.orNull
                    ?: SyncProjectFilesTask.detectSdkVersion(project.projectDir)
                val lang = projectFiles.overrideLanguage.orNull
                    ?: SyncProjectFilesTask.detectLanguage(project.projectDir)

                if (version == "unknown") {
                    project.logger.info(
                        "Fluxzero project files: No SDK version detected. " +
                            "Project files will not be synced unless overrideSdkVersion is set."
                    )
                } else {
                    project.logger.info(
                        "Fluxzero project files configured: version=$version, language=$lang"
                    )
                }
            } else {
                project.logger.info("Fluxzero project files sync is disabled")
            }
        }
    }
}
