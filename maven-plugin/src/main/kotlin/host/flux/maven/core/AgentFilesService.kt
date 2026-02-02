package host.flux.maven.core

import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import java.nio.file.Path

private val logger = KotlinLogging.logger {}

/**
 * Service for synchronizing agent files in Fluxzero projects.
 */
class AgentFilesService(
    private val gitHubClient: GitHubReleaseClient = GitHubReleaseClient()
) {
    /**
     * Synchronizes agent files for the project.
     */
    fun syncAgentFiles(
        projectDir: Path,
        forceUpdate: Boolean = false,
        language: Language? = null,
        version: String? = null
    ): SyncResult = runBlocking {
        try {
            logger.info { "Starting agent files sync for $projectDir" }

            val sdkVersion = version ?: SdkVersionDetector.detect(projectDir)
            if (sdkVersion == null) {
                logger.info { "No SDK version found, skipping sync" }
                return@runBlocking SyncResult.Skipped(
                    "No Fluxzero SDK dependency found in project"
                )
            }

            val projectLanguage = language ?: LanguageDetector.detect(projectDir)
            logger.info { "Using language: $projectLanguage, version: $sdkVersion" }

            logger.info { "Downloading agent files for $projectLanguage version $sdkVersion" }
            val zipData = gitHubClient.downloadAgentFiles(projectLanguage, sdkVersion)

            AgentFilesExtractor.cleanExistingFiles(projectDir)
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
}
