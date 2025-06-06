package host.flux.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

class Upgrade(
    private val installer: Installer = DefaultInstaller(),
) : CliktCommand() {

    override fun help(context: Context): String = "Download and install the latest flux-cli release"

    override fun run() {
        val version = installer.installLatest()
        echo("flux-cli upgraded to $version")
    }
}

interface Installer {
    fun installLatest(): String
}

internal class DefaultInstaller(
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.ALWAYS)
        .build(),
    private val homeDir: Path = Paths.get(System.getProperty("user.home")),
) : Installer {

    override fun installLatest(): String {
        val latest = fetchLatestTag() ?: throw RuntimeException("Could not determine latest release")
        install(latest)
        return latest
    }

    private fun fetchLatestTag(): String? {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("https://api.github.com/repos/flux-capacitor-io/flux-cli/releases/latest"))
            .header("Accept", "application/json")
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) return null
        val tagRegex = "\"tag_name\"\\s*:\\s*\"([^\"]+)\"".toRegex()
        return tagRegex.find(response.body())?.groupValues?.get(1)
    }

    private fun install(tag: String) {
        val installDir = homeDir.resolve(".flux")
        Files.createDirectories(installDir)

        val jarUrl = "https://github.com/flux-capacitor-io/flux-cli/releases/download/$tag/flux-cli.jar"
        val jarRequest = HttpRequest.newBuilder()
            .uri(URI.create(jarUrl))
            .build()
        val jarResponse = httpClient.send(jarRequest, HttpResponse.BodyHandlers.ofInputStream())
        if (jarResponse.statusCode() != 200) {
            throw RuntimeException("Failed to download flux-cli.jar")
        }
        val jarPath = installDir.resolve("flux-cli.jar")
        jarResponse.body().use { input ->
            Files.copy(input, jarPath, StandardCopyOption.REPLACE_EXISTING)
        }

        val cliScriptPath = installDir.resolve("cli")
        if (Files.notExists(cliScriptPath)) {
            val scriptContent = "#!/usr/bin/env sh\njava -jar ~/.flux/flux-cli.jar \$@\n"
            Files.writeString(cliScriptPath, scriptContent)
        }
        cliScriptPath.toFile().setExecutable(true)
    }
}
