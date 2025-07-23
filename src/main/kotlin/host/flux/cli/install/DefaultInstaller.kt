package host.flux.cli.install

import host.flux.cli.UpdateChecker
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

internal const val LATEST_API_URL =
    "https://api.github.com/repos/flux-capacitor-io/flux-cli/releases/latest"
internal const val JAR_URL_TEMPLATE =
    "https://github.com/flux-capacitor-io/flux-cli/releases/download/%s/fluxzero-cli.jar"
internal const val SCRIPT_TEMPLATE = "#!/usr/bin/env sh\njava -jar ~/.flux/fluxzero-cli.jar \"\$@\"\n"

open class DefaultInstaller(
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.ALWAYS)
        .build(),
    private val homeDir: Path = Paths.get(System.getProperty("user.home")),
) : Installer {

    override fun install(): InstallResult {
        val latest = fetchLatestTag() ?: throw IllegalStateException("Could not determine latest release")
        val current = getCurrentVersion()
        
        return when {
            current == null -> {
                installVersion(latest)
                InstallResult.FreshInstall(latest)
            }
            UpdateChecker.isNewer(current, latest) -> {
                installVersion(latest)
                InstallResult.Upgraded(current, latest)
            }
            else -> InstallResult.AlreadyLatest(current)
        }
    }

    private fun fetchLatestTag(): String? {
        val request = HttpRequest.newBuilder()
            .uri(URI.create(LATEST_API_URL))
            .header("Accept", "application/json")
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) return null
        val tagRegex = "\"tag_name\"\\s*:\\s*\"([^\"]+)\"".toRegex()
        return tagRegex.find(response.body())?.groupValues?.get(1)
    }

    open fun getCurrentVersion(): String? {
        return try {
            // Try to get version from the currently running JAR
            DefaultInstaller::class.java.`package`.implementationVersion
        } catch (_: Exception) {
            null
        }
    }

    private fun installVersion(tag: String) {
        val installDir = homeDir.resolve(".fluxzero")
        Files.createDirectories(installDir)

        val jarUrl = JAR_URL_TEMPLATE.format(tag)
        val jarRequest = HttpRequest.newBuilder()
            .uri(URI.create(jarUrl))
            .build()
        val jarResponse = httpClient.send(jarRequest, HttpResponse.BodyHandlers.ofInputStream())
        if (jarResponse.statusCode() != 200) {
            throw IllegalStateException("Failed to download fluxzero-cli.jar")
        }
        val jarPath = installDir.resolve("fluxzero-cli.jar")
        jarResponse.body().use { input ->
            Files.copy(input, jarPath, StandardCopyOption.REPLACE_EXISTING)
        }

        val cliScriptPath = installDir.resolve("cli")
        if (Files.notExists(cliScriptPath)) {
            Files.writeString(cliScriptPath, SCRIPT_TEMPLATE)
        }
        cliScriptPath.toFile().setExecutable(true)
    }
}
