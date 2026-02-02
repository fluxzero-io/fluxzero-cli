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
                logger.info { "No SDK version found, skipping sync" }
                return@runBlocking SyncResult.Skipped(
                    "No Fluxzero SDK dependency found in project"
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
