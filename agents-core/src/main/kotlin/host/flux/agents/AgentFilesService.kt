package host.flux.agents

import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import java.nio.file.Path

private val logger = KotlinLogging.logger {}

/**
 * Service for synchronizing agent files in Fluxzero projects.
 */
interface AgentFilesService {
    /**
     * Synchronizes agent files for the project.
     *
     * @param projectDir The root directory of the project
     * @param forceUpdate If true, forces re-download even if files exist
     * @param language Override language detection
     * @param version Override version detection (use specific SDK version)
     * @return The result of the sync operation
     */
    fun syncAgentFiles(
        projectDir: Path,
        forceUpdate: Boolean = false,
        language: Language? = null,
        version: String? = null
    ): SyncResult

    /**
     * Checks if there are updates available for agent files.
     *
     * @param projectDir The root directory of the project
     * @return The update check result
     */
    fun checkForUpdates(projectDir: Path): UpdateCheckResult
}

/**
 * Default implementation of [AgentFilesService].
 */
class DefaultAgentFilesService(
    private val gitHubClient: GitHubReleaseClient = GitHubReleaseClient()
) : AgentFilesService {

    companion object {
        /**
         * Minimum SDK version that includes agent files in releases.
         * Versions before this do not have agent files available.
         */
        val MIN_SUPPORTED_VERSION = SemanticVersion(1, 75, 1)
    }

    override fun syncAgentFiles(
        projectDir: Path,
        forceUpdate: Boolean,
        language: Language?,
        version: String?
    ): SyncResult = runBlocking {
        try {
            logger.info { "Starting agent files sync for $projectDir" }

            // Detect or use provided version
            val sdkVersion = version ?: SdkVersionDetector.detect(projectDir)
            if (sdkVersion == null) {
                logger.error { "No SDK version found - cannot sync agent files" }
                return@runBlocking SyncResult.Failed(
                    error = "No Fluxzero SDK dependency found in project. " +
                        "Add fluxzero-sdk or fluxzero-bom dependency, or set overrideSdkVersion.",
                    cause = null
                )
            }

            // Check minimum version requirement
            val parsedVersion = SemanticVersion.parse(sdkVersion)
            if (parsedVersion != null && parsedVersion < MIN_SUPPORTED_VERSION) {
                logger.info { "SDK version $sdkVersion is below minimum supported version $MIN_SUPPORTED_VERSION" }
                return@runBlocking SyncResult.Skipped(
                    "Agent files are only available for SDK version $MIN_SUPPORTED_VERSION and later. " +
                        "Current version: $sdkVersion"
                )
            }

            // Detect or use provided language
            val projectLanguage = language ?: LanguageDetector.detect(projectDir)
            logger.info { "Using language: $projectLanguage, version: $sdkVersion" }

            // Download agent files
            logger.info { "Downloading agent files for $projectLanguage version $sdkVersion" }
            val zipData = gitHubClient.downloadAgentFiles(projectLanguage, sdkVersion)

            // Clean existing files
            AgentFilesExtractor.cleanExistingFiles(projectDir)

            // Extract new files
            val extractedFiles = AgentFilesExtractor.extract(zipData, projectDir)

            logger.info { "Successfully synced ${extractedFiles.size} agent files" }
            SyncResult.Updated(
                version = sdkVersion,
                filesWritten = extractedFiles,
                language = projectLanguage
            )
        } catch (e: GitHubApiException) {
            logger.error(e) { "GitHub API error during sync" }
            SyncResult.Failed(
                error = "Failed to fetch agent files: ${e.message}",
                cause = e
            )
        } catch (e: Exception) {
            logger.error(e) { "Unexpected error during sync" }
            SyncResult.Failed(
                error = "Sync failed: ${e.message}",
                cause = e
            )
        }
    }

    override fun checkForUpdates(projectDir: Path): UpdateCheckResult = runBlocking {
        val currentVersion = SdkVersionDetector.detect(projectDir)
        val latestRelease = gitHubClient.getLatestRelease()

        UpdateCheckResult(
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
