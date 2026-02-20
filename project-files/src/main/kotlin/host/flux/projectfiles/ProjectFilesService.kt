package host.flux.projectfiles

import mu.KotlinLogging
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

private val logger = KotlinLogging.logger {}

/**
 * Service for synchronizing project files in Fluxzero projects.
 */
interface ProjectFilesService {
    /**
     * Synchronizes project files for the project.
     *
     * @param projectDir The root directory of the project
     * @param forceUpdate If true, forces re-download even if files exist
     * @param language Override language detection
     * @param version Override version detection (use specific SDK version)
     * @return The result of the sync operation
     */
    fun syncProjectFiles(
        projectDir: Path,
        forceUpdate: Boolean = false,
        language: Language? = null,
        version: String? = null
    ): SyncResult

    /**
     * Checks if there are updates available for project files.
     *
     * @param projectDir The root directory of the project
     * @return The update check result
     */
    fun checkForUpdates(projectDir: Path): UpdateCheckResult
}

/**
 * Default implementation of [ProjectFilesService].
 */
class DefaultProjectFilesService(
    private val gitHubClient: GitHubReleaseClient = GitHubReleaseClient()
) : ProjectFilesService {

    companion object {
        /**
         * Minimum SDK version that includes project files in releases.
         * Versions before this do not have project files available.
         */
        val MIN_SUPPORTED_VERSION = SemanticVersion(1, 75, 1)
    }

    override fun syncProjectFiles(
        projectDir: Path,
        forceUpdate: Boolean,
        language: Language?,
        version: String?
    ): SyncResult {
        return try {
            logger.info { "Starting project files sync for $projectDir" }

            // Detect or use provided version
            val sdkVersion = version ?: SdkVersionDetector.detect(projectDir)
            if (sdkVersion == null) {
                logger.error { "No SDK version found - cannot sync project files" }
                return SyncResult.Failed(
                    error = buildString {
                        appendLine("No Fluxzero SDK dependency found in project.")
                        appendLine()
                        appendLine("To fix this, add the Fluxzero SDK dependency to your project:")
                        appendLine()
                        appendLine("For Gradle (build.gradle.kts):")
                        appendLine("  dependencies {")
                        appendLine("      implementation(\"io.fluxzero:fluxzero-sdk:1.75.1\")")
                        appendLine("  }")
                        appendLine()
                        appendLine("For Gradle with version catalog (gradle/libs.versions.toml):")
                        appendLine("  [versions]")
                        appendLine("  fluxzero = \"1.75.1\"")
                        appendLine()
                        appendLine("  [libraries]")
                        appendLine("  fluxzero-sdk = { module = \"io.fluxzero:fluxzero-sdk\", version.ref = \"fluxzero\" }")
                        appendLine()
                        appendLine("For Maven (pom.xml):")
                        appendLine("  <dependency>")
                        appendLine("      <groupId>io.fluxzero</groupId>")
                        appendLine("      <artifactId>fluxzero-sdk</artifactId>")
                        appendLine("      <version>1.75.1</version>")
                        appendLine("  </dependency>")
                        appendLine()
                        appendLine("Alternatively, you can bypass auto-detection by setting overrideSdkVersion:")
                        appendLine("  • Gradle: fluxzero { projectFiles { overrideSdkVersion.set(\"1.75.1\") } }")
                        appendLine("  • Maven: <overrideSdkVersion>1.75.1</overrideSdkVersion>")
                    },
                    cause = null
                )
            }

            // Check minimum version requirement
            val parsedVersion = SemanticVersion.parse(sdkVersion)
            if (parsedVersion != null && parsedVersion < MIN_SUPPORTED_VERSION) {
                logger.info { "SDK version $sdkVersion is below minimum supported version $MIN_SUPPORTED_VERSION" }
                return SyncResult.Skipped(
                    "Agent files are only available for SDK version $MIN_SUPPORTED_VERSION and later. " +
                        "Current version: $sdkVersion"
                )
            }

            // Detect or use provided language
            val projectLanguage = language ?: LanguageDetector.detect(projectDir)
            logger.info { "Using language: $projectLanguage, version: $sdkVersion" }

            // Check if already up-to-date
            if (!forceUpdate) {
                val syncVersionFile = projectDir.resolve(ProjectFilesExtractor.PROJECT_FILES_DIR).resolve(".sync-version")
                if (Files.exists(syncVersionFile)) {
                    val savedState = Files.readString(syncVersionFile).trim()
                    val currentState = "$sdkVersion:${projectLanguage.name.lowercase()}"
                    if (savedState == currentState) {
                        logger.info { "Project files already up-to-date for $projectLanguage version $sdkVersion" }
                        return SyncResult.UpToDate(version = sdkVersion)
                    }
                }
            }

            // Download project files
            logger.info { "Downloading project files for $projectLanguage version $sdkVersion" }
            val zipData = gitHubClient.downloadProjectFiles(projectLanguage, sdkVersion)

            // Clean existing files
            ProjectFilesExtractor.cleanExistingFiles(projectDir)

            // Extract new files
            val extractedFiles = ProjectFilesExtractor.extract(zipData, projectDir)

            // Record synced version for up-to-date checking
            val syncVersionFile = projectDir.resolve(ProjectFilesExtractor.PROJECT_FILES_DIR).resolve(".sync-version")
            Files.writeString(syncVersionFile, "$sdkVersion:${projectLanguage.name.lowercase()}")

            logger.info { "Successfully synced ${extractedFiles.size} project files" }
            SyncResult.Updated(
                version = sdkVersion,
                filesWritten = extractedFiles,
                language = projectLanguage
            )
        } catch (e: GitHubApiException) {
            logger.error(e) { "GitHub API error during sync" }
            SyncResult.Failed(
                error = "Failed to fetch project files: ${e.message}",
                cause = e
            )
        } catch (e: IOException) {
            logger.warn(e) { "Network error during project files sync - continuing without sync" }
            SyncResult.Skipped(
                "Could not reach GitHub to download project files (${e.javaClass.simpleName}: ${e.message}). " +
                    "Build will continue without syncing. Check your internet connection if this persists."
            )
        } catch (e: Exception) {
            logger.error(e) { "Unexpected error during sync" }
            SyncResult.Failed(
                error = "Sync failed: ${e.message}",
                cause = e
            )
        }
    }

    override fun checkForUpdates(projectDir: Path): UpdateCheckResult {
        val currentVersion = SdkVersionDetector.detect(projectDir)
        val latestRelease = gitHubClient.getLatestRelease()

        return UpdateCheckResult(
            currentVersion = currentVersion,
            latestVersion = latestRelease.tagName,
            updateAvailable = currentVersion != null && currentVersion != latestRelease.tagName
        )
    }
}

/**
 * Represents a semantic version (major.minor.patch).
 */
data class SemanticVersion(
    val major: Int,
    val minor: Int,
    val patch: Int
) : Comparable<SemanticVersion> {

    override fun compareTo(other: SemanticVersion): Int {
        val majorCmp = major.compareTo(other.major)
        if (majorCmp != 0) return majorCmp

        val minorCmp = minor.compareTo(other.minor)
        if (minorCmp != 0) return minorCmp

        return patch.compareTo(other.patch)
    }

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        private val VERSION_PATTERN = Regex("""^v?(\d+)\.(\d+)\.(\d+)""")

        /**
         * Parses a version string into a SemanticVersion.
         * Accepts formats like "1.75.1", "v1.75.1", "1.75.1-SNAPSHOT".
         * Returns null if the version string cannot be parsed.
         */
        fun parse(version: String): SemanticVersion? {
            val match = VERSION_PATTERN.find(version) ?: return null
            return try {
                SemanticVersion(
                    major = match.groupValues[1].toInt(),
                    minor = match.groupValues[2].toInt(),
                    patch = match.groupValues[3].toInt()
                )
            } catch (e: NumberFormatException) {
                null
            }
        }
    }
}
