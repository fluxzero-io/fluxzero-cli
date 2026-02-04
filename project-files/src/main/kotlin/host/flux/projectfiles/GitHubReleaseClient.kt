package host.flux.projectfiles

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

private val logger = KotlinLogging.logger {}

/**
 * Client for interacting with GitHub Releases API.
 *
 * Fetches project files from GitHub release assets.
 * Uses Java's built-in HttpClient to minimize dependencies.
 */
class GitHubReleaseClient(
    private val repo: String = DEFAULT_REPO,
    private val httpClient: HttpClient = createDefaultHttpClient()
) {
    companion object {
        const val DEFAULT_REPO = "fluxzero-io/fluxzero-sdk-java"
        private const val GITHUB_API_BASE = "https://api.github.com"

        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        private fun createDefaultHttpClient(): HttpClient {
            return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build()
        }
    }

    /**
     * Gets the latest release from the repository.
     */
    fun getLatestRelease(): Release {
        logger.debug { "Fetching latest release for $repo" }
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$GITHUB_API_BASE/repos/$repo/releases/latest"))
            .header("Accept", "application/vnd.github.v3+json")
            .header("User-Agent", "fluxzero-project-files")
            .GET()
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

        if (response.statusCode() !in 200..299) {
            throw GitHubApiException("Failed to fetch latest release: ${response.statusCode()}")
        }

        return json.decodeFromString<Release>(response.body())
    }

    /**
     * Gets a specific release by tag name.
     */
    fun getReleaseByTag(tag: String): Release {
        logger.debug { "Fetching release for tag $tag from $repo" }
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$GITHUB_API_BASE/repos/$repo/releases/tags/$tag"))
            .header("Accept", "application/vnd.github.v3+json")
            .header("User-Agent", "fluxzero-project-files")
            .GET()
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

        if (response.statusCode() !in 200..299) {
            throw GitHubApiException("Failed to fetch release for tag $tag: ${response.statusCode()}")
        }

        return json.decodeFromString<Release>(response.body())
    }

    /**
     * Gets the download URL for project files for the given language and version.
     */
    fun getProjectFilesDownloadUrl(language: Language, version: String): String {
        val assetName = "project-${language.assetSuffix}.zip"
        logger.debug { "Looking for asset $assetName in version $version" }

        val release = getReleaseByTag(version)
        val asset = release.assets.find { it.name == assetName }
            ?: throw GitHubApiException("Asset $assetName not found in release $version")

        return asset.browserDownloadUrl
    }

    /**
     * Downloads project files for the given language and version.
     */
    fun downloadProjectFiles(language: Language, version: String): ByteArray {
        val url = getProjectFilesDownloadUrl(language, version)
        logger.info { "Downloading project files from $url" }

        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Accept", "application/octet-stream")
            .header("User-Agent", "fluxzero-project-files")
            .GET()
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray())

        if (response.statusCode() !in 200..299) {
            throw GitHubApiException("Failed to download project files: ${response.statusCode()}")
        }

        return response.body()
    }

    /**
     * Downloads project files and returns them as an InputStream.
     */
    fun downloadProjectFilesAsStream(language: Language, version: String): InputStream {
        return downloadProjectFiles(language, version).inputStream()
    }
}

@Serializable
data class Release(
    val id: Long,
    @SerialName("tag_name")
    val tagName: String,
    val name: String? = null,
    val assets: List<ReleaseAsset> = emptyList(),
    val prerelease: Boolean = false,
    val draft: Boolean = false,
    @SerialName("published_at")
    val publishedAt: String? = null
)

@Serializable
data class ReleaseAsset(
    val id: Long,
    val name: String,
    val size: Long = 0,
    @SerialName("browser_download_url")
    val browserDownloadUrl: String,
    @SerialName("content_type")
    val contentType: String? = null
)

class GitHubApiException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
