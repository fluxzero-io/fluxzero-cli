package host.flux.gradle

import host.flux.projectfiles.DefaultProjectFilesService
import host.flux.projectfiles.Language
import host.flux.projectfiles.LanguageDetector
import host.flux.projectfiles.SdkVersionDetector
import host.flux.projectfiles.SyncResult
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

/**
 * Gradle task that synchronizes project files for Fluxzero projects.
 *
 * This task:
 * - Detects the SDK version from project dependencies
 * - Detects the project language (Kotlin or Java)
 * - Downloads the appropriate project files from GitHub releases
 * - Extracts them to the project directory
 *
 * The task uses Gradle's incremental build support:
 * - If the SDK version or language hasn't changed, the task is UP-TO-DATE
 * - No network requests are made unless necessary
 */
abstract class SyncProjectFilesTask : DefaultTask() {

    init {
        description = "Syncs project files for this Fluxzero project"
        group = "fluxzero"
    }

    /**
     * The SDK version to use. This is the primary cache key.
     * When this changes, the task will re-run.
     */
    @get:Input
    abstract val sdkVersion: Property<String>

    /**
     * The detected/configured language.
     */
    @get:Input
    abstract val language: Property<String>

    /**
     * Whether to force re-download even if files exist.
     */
    @get:Input
    @get:Optional
    abstract val forceUpdate: Property<Boolean>

    /**
     * The output directory for project files (.fluxzero/).
     * Using this as the output ensures Gradle tracks file changes.
     */
    @get:OutputDirectory
    abstract val projectFilesDir: DirectoryProperty

    /**
     * The project directory (not used as input to avoid unnecessary rebuilds).
     */
    @get:Internal
    abstract val projectDir: DirectoryProperty

    @TaskAction
    fun syncProjectFiles() {
        val projectPath = projectDir.get().asFile.toPath()
        val version = sdkVersion.get()
        val lang = Language.fromString(language.get())
            ?: throw GradleException("Invalid language: ${language.get()}. Must be 'kotlin' or 'java'.")

        logger.lifecycle("Syncing project files for Fluxzero SDK $version ($lang)")

        val service = DefaultProjectFilesService()
        val result = service.syncProjectFiles(
            projectDir = projectPath,
            forceUpdate = forceUpdate.getOrElse(false),
            language = lang,
            version = version
        )

        when (result) {
            is SyncResult.Updated -> {
                logger.lifecycle("Project files updated to version ${result.version}")
                logger.info("Files written: ${result.filesWritten.joinToString(", ")}")
            }
            is SyncResult.UpToDate -> {
                logger.lifecycle("Project files are up to date (version ${result.version})")
            }
            is SyncResult.Skipped -> {
                logger.warn("Project files sync skipped: ${result.reason}")
            }
            is SyncResult.Failed -> {
                throw GradleException("Failed to sync project files: ${result.error}", result.cause)
            }
        }
    }

    companion object {
        /**
         * Detects the SDK version for use as task input.
         * Returns "unknown" if not detectable to avoid task configuration errors.
         */
        fun detectSdkVersion(projectDir: java.io.File): String {
            return SdkVersionDetector.detect(projectDir.toPath()) ?: "unknown"
        }

        /**
         * Detects the language for use as task input.
         */
        fun detectLanguage(projectDir: java.io.File): String {
            return LanguageDetector.detect(projectDir.toPath()).name.lowercase()
        }
    }
}
