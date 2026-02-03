package host.flux.agents

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import java.io.InputStream

private val logger = KotlinLogging.logger {}

/**
 * Client for interacting with GitHub Releases API.
 *
 * Fetches agent files from GitHub release assets.
 */
class GitHubReleaseClient(
    private val repo: String = DEFAULT_REPO,
    private val httpClient: HttpClient = createDefaultHttpClient()
) {
    companion object {
        const val DEFAULT_REPO = "fluxzero-io/fluxzero-sdk-java"
        private const val GITHUB_API_BASE = "https://api.github.com"

        private fun createDefaultHttpClient(): HttpClient {
            return HttpClient(CIO) {
                install(ContentNegotiation) {
                    json(Json {
                        ignoreUnknownKeys = true
                        isLenient = true
                    })
                }
            }
        }
    }

    /**
     * Gets the latest release from the repository.
     */
    suspend fun getLatestRelease(): Release {
        logger.debug { "Fetching latest release for $repo" }
        val response: HttpResponse = httpClient.get("$GITHUB_API_BASE/repos/$repo/releases/latest") {
            headers {
                append(HttpHeaders.Accept, "application/vnd.github.v3+json")
                append(HttpHeaders.UserAgent, "fluxzero-agents")
            }
        }

        if (!response.status.isSuccess()) {
            throw GitHubApiException("Failed to fetch latest release: ${response.status}")
        }

        return response.body()
    }

    /**
     * Gets a specific release by tag name.
     */
    suspend fun getReleaseByTag(tag: String): Release {
        logger.debug { "Fetching release for tag $tag from $repo" }
        val response: HttpResponse = httpClient.get("$GITHUB_API_BASE/repos/$repo/releases/tags/$tag") {
            headers {
                append(HttpHeaders.Accept, "application/vnd.github.v3+json")
                append(HttpHeaders.UserAgent, "fluxzero-agents")
            }
        }

        if (!response.status.isSuccess()) {
            throw GitHubApiException("Failed to fetch release for tag $tag: ${response.status}")
        }

        return response.body()
    }

    /**
     * Gets the download URL for agent files for the given language and version.
     */
    suspend fun getAgentFilesDownloadUrl(language: Language, version: String): String {
        val assetName = "agents-${language.assetSuffix}.zip"
        logger.debug { "Looking for asset $assetName in version $version" }

        val release = getReleaseByTag(version)
        val asset = release.assets.find { it.name == assetName }
            ?: throw GitHubApiException("Asset $assetName not found in release $version")

        return asset.browserDownloadUrl
    }

    /**
     * Downloads agent files for the given language and version.
     */
    suspend fun downloadAgentFiles(language: Language, version: String): ByteArray {
        val url = getAgentFilesDownloadUrl(language, version)
        logger.info { "Downloading agent files from $url" }

        val response: HttpResponse = httpClient.get(url) {
            headers {
                append(HttpHeaders.Accept, "application/octet-stream")
                append(HttpHeaders.UserAgent, "fluxzero-agents")
            }
        }

        if (!response.status.isSuccess()) {
            throw GitHubApiException("Failed to download agent files: ${response.status}")
        }

        return response.body()
    }

    /**
     * Downloads agent files and returns them as an InputStream.
     */
    suspend fun downloadAgentFilesAsStream(language: Language, version: String): InputStream {
        return downloadAgentFiles(language, version).inputStream()
    }

    /**
     * Closes the HTTP client.
     */
    fun close() {
        httpClient.close()
    }
}

@Serializable
data class Release(
    val id: Long,
    @SerialName("tag_name")
    val tagName: String,
    val name: String?,
    val assets: List<ReleaseAsset>,
    val prerelease: Boolean,
    val draft: Boolean,
    @SerialName("published_at")
    val publishedAt: String?
)

@Serializable
data class ReleaseAsset(
    val id: Long,
    val name: String,
    val size: Long,
    @SerialName("browser_download_url")
    val browserDownloadUrl: String,
    @SerialName("content_type")
    val contentType: String?
)

class GitHubApiException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
