package host.flux.desktop.services

import host.flux.desktop.model.CliStatus
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration

data class ReleaseAsset(
    val name: String,
    val downloadUrl: String
)

data class CliRelease(
    val tagName: String,
    val assets: List<ReleaseAsset>
) {
    fun downloadUrlFor(target: PlatformTarget): String {
        val assetName = target.cliReleaseAssetName
        return assets.firstOrNull { it.name == assetName }?.downloadUrl
            ?: "https://github.com/fluxzero-io/fluxzero-cli/releases/download/$tagName/$assetName"
    }
}

class CliReleaseClient(
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.ALWAYS)
        .build(),
    private val latestReleaseUrl: String = "https://api.github.com/repos/fluxzero-io/fluxzero-cli/releases/latest"
) {
    fun fetchLatestRelease(): CliRelease {
        val request = HttpRequest.newBuilder()
            .uri(URI.create(latestReleaseUrl))
            .timeout(Duration.ofSeconds(30))
            .header("Accept", "application/json")
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) {
            error("GitHub returned HTTP ${response.statusCode()} while checking the latest Fluxzero CLI release.")
        }
        return parseRelease(response.body())
    }

    fun download(url: String): InputStream {
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofMinutes(2))
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
        if (response.statusCode() != 200) {
            response.body().close()
            error("Could not download Fluxzero CLI from $url (HTTP ${response.statusCode()}).")
        }
        return response.body()
    }

    companion object {
        private val tagRegex = Regex(""""tag_name"\s*:\s*"([^"]+)"""")
        private val assetRegex = Regex(
            """"name"\s*:\s*"([^"]+)"[\s\S]*?"browser_download_url"\s*:\s*"([^"]+)""""
        )

        fun parseRelease(json: String): CliRelease {
            val tag = tagRegex.find(json)?.groupValues?.get(1)
                ?: error("Could not parse tag_name from GitHub release response.")
            val assets = assetRegex.findAll(json).map {
                ReleaseAsset(
                    name = it.groupValues[1],
                    downloadUrl = it.groupValues[2].replace("\\/", "/")
                )
            }.toList()
            return CliRelease(tagName = tag, assets = assets)
        }
    }
}

class CliRuntimeService(
    private val paths: AppPaths,
    private val target: PlatformTarget = PlatformDetector.detect(),
    private val releaseClient: CliReleaseClient = CliReleaseClient(),
    private val commandRunner: CommandRunner = ProcessCommandRunner()
) {
    fun ensureLatestCli(): CliStatus {
        Files.createDirectories(paths.binDir)
        val installedVersion = installedVersion()

        val latestRelease = try {
            releaseClient.fetchLatestRelease()
        } catch (e: Exception) {
            if (Files.isRegularFile(paths.cliExecutable)) {
                return CliStatus(
                    executablePath = paths.cliExecutable.toString(),
                    version = installedVersion,
                    latestVersion = null,
                    updated = false,
                    message = "Using installed CLI; latest version check failed: ${e.message}"
                )
            }
            throw e
        }

        val latest = latestRelease.tagName
        if (Files.isRegularFile(paths.cliExecutable) && versionsEqual(installedVersion, latest)) {
            return CliStatus(
                executablePath = paths.cliExecutable.toString(),
                version = installedVersion,
                latestVersion = latest,
                updated = false,
                message = "Fluxzero CLI is up to date."
            )
        }

        downloadCli(latestRelease.downloadUrlFor(target), paths.cliExecutable)
        val versionAfterDownload = installedVersion()
        return CliStatus(
            executablePath = paths.cliExecutable.toString(),
            version = versionAfterDownload ?: latest,
            latestVersion = latest,
            updated = true,
            message = "Downloaded Fluxzero CLI $latest."
        )
    }

    fun listTemplates(): List<String> {
        if (!Files.isRegularFile(paths.cliExecutable)) {
            return emptyList()
        }
        val result = commandRunner.run(listOf(paths.cliExecutable.toString(), "templates", "list"))
        if (!result.successful) {
            return emptyList()
        }
        return result.output.lineSequence()
            .map { it.trim().removePrefix("-").trim() }
            .map { it.substringBefore(":").trim() }
            .filter { it.isNotBlank() }
            .filterNot { it.startsWith("A new version", ignoreCase = true) }
            .filterNot { it.startsWith("WARNING", ignoreCase = true) }
            .toList()
    }

    fun installedVersion(): String? {
        if (!Files.isRegularFile(paths.cliExecutable)) {
            return null
        }
        val result = commandRunner.run(listOf(paths.cliExecutable.toString(), "version"), timeout = Duration.ofSeconds(15))
        if (!result.successful) {
            return null
        }
        return result.output.lineSequence()
            .map { it.trim() }
            .lastOrNull { VERSION_REGEX.matches(it) }
    }

    private fun downloadCli(downloadUrl: String, targetPath: Path) {
        Files.createDirectories(targetPath.parent)
        val tempPath = targetPath.resolveSibling("${targetPath.fileName}.tmp")
        try {
            releaseClient.download(downloadUrl).use { input ->
                Files.copy(input, tempPath, StandardCopyOption.REPLACE_EXISTING)
            }
            if (target.os != OperatingSystem.WINDOWS) {
                tempPath.toFile().setExecutable(true, false)
            }
            try {
                Files.move(tempPath, targetPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: Exception) {
                Files.move(tempPath, targetPath, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: Exception) {
            Files.deleteIfExists(tempPath)
            throw e
        }
    }

    private fun versionsEqual(left: String?, right: String?): Boolean {
        if (left == null || right == null) {
            return false
        }
        return left.removePrefix("v") == right.removePrefix("v")
    }

    companion object {
        private val VERSION_REGEX = Regex("""v?\d+\.\d+\.\d+(?:[-.A-Za-z0-9]+)?""")
    }
}
