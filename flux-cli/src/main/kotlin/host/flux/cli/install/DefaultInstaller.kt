package host.flux.cli.install

import host.flux.cli.UpdateChecker
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

internal const val PRIMARY_LATEST_API_URL =
    "https://api.github.com/repos/flux-capacitor-io/flux-cli/releases/latest"
internal const val FALLBACK_LATEST_API_URL =
    "https://api.github.com/repos/fluxzero/cli/releases/latest"
internal const val PRIMARY_BINARY_URL_TEMPLATE =
    "https://github.com/flux-capacitor-io/flux-cli/releases/download/%s/%s"
internal const val FALLBACK_BINARY_URL_TEMPLATE =
    "https://github.com/fluxzero/cli/releases/download/%s/%s"

open class DefaultInstaller(
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.ALWAYS)
        .build(),
    private val homeDir: Path = Paths.get(System.getProperty("user.home")),
) : Installer {

    override fun install(): InstallResult {
        val latest = fetchLatestTag() ?: throw IllegalStateException(
            "Could not determine latest release. Please check your internet connection or try reinstalling using the installation script at https://fluxzero.io/docs/getting-started"
        )
        val current = getCurrentVersion()
        
        return when {
            current == null -> {
                try {
                    installVersion(latest)
                    InstallResult.FreshInstall(latest)
                } catch (e: Exception) {
                    throw IllegalStateException(
                        "Installation failed: ${e.message}. Please try reinstalling using the installation script at https://fluxzero.io/docs/getting-started", e
                    )
                }
            }
            UpdateChecker.isNewer(current, latest) -> {
                try {
                    installVersion(latest)
                    InstallResult.Upgraded(current, latest)
                } catch (e: Exception) {
                    throw IllegalStateException(
                        "Upgrade failed: ${e.message}. Please try reinstalling using the installation script at https://fluxzero.io/docs/getting-started", e
                    )
                }
            }
            else -> InstallResult.AlreadyLatest(current)
        }
    }

    private fun fetchLatestTag(): String? {
        // Try primary repository first
        val primaryResult = tryFetchLatestTag(PRIMARY_LATEST_API_URL)
        if (primaryResult != null) return primaryResult
        
        // Fall back to alternative repository
        return tryFetchLatestTag(FALLBACK_LATEST_API_URL)
    }
    
    private fun tryFetchLatestTag(apiUrl: String): String? {
        return try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .header("Accept", "application/json")
                .build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() != 200) return null
            val tagRegex = "\"tag_name\"\\s*:\\s*\"([^\"]+)\"".toRegex()
            tagRegex.find(response.body())?.groupValues?.get(1)
        } catch (_: Exception) {
            null
        }
    }

    open fun getCurrentVersion(): String? {
        return try {
            // Try to get version from the currently running JAR/native binary
            DefaultInstaller::class.java.`package`.implementationVersion
        } catch (_: Exception) {
            // If that fails, try to detect installed version by checking filesystem
            detectInstalledVersion()
        }
    }
    
    private fun detectInstalledVersion(): String? {
        // Check for current installation in ~/.fluxzero/bin/fz
        val currentBinary = homeDir.resolve(".fluxzero").resolve("bin").resolve("fz")
        if (Files.exists(currentBinary)) {
            // For native binaries, we can try to run them to get version
            return tryGetVersionFromBinary(currentBinary)
        }
        
        // Check for legacy installation in ~/.flux/fluxzero-cli.jar
        val legacyJar = homeDir.resolve(".flux").resolve("fluxzero-cli.jar")
        if (Files.exists(legacyJar)) {
            return tryGetVersionFromJar(legacyJar)
        }
        
        return null
    }
    
    private fun tryGetVersionFromBinary(binaryPath: Path): String? {
        return try {
            val process = ProcessBuilder(binaryPath.toString(), "version")
                .redirectOutput(ProcessBuilder.Redirect.PIPE)
                .redirectError(ProcessBuilder.Redirect.PIPE)
                .start()
            
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
            
            // Extract version from output (assuming format like "fluxzero-cli 1.2.3")
            val versionRegex = "fluxzero-cli\\s+([\\d.]+)".toRegex()
            versionRegex.find(output)?.groupValues?.get(1)
        } catch (_: Exception) {
            null
        }
    }
    
    private fun tryGetVersionFromJar(jarPath: Path): String? {
        return try {
            val process = ProcessBuilder("java", "-jar", jarPath.toString(), "version")
                .redirectOutput(ProcessBuilder.Redirect.PIPE)
                .redirectError(ProcessBuilder.Redirect.PIPE)
                .start()
            
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
            
            // Extract version from output  
            val versionRegex = "fluxzero-cli\\s+([\\d.]+)".toRegex()
            versionRegex.find(output)?.groupValues?.get(1)
        } catch (_: Exception) {
            null
        }
    }

    private fun detectPlatform(): Pair<String, String> {
        val osName = System.getProperty("os.name").lowercase()
        val osArch = System.getProperty("os.arch").lowercase()
        
        val platform = when {
            osName.contains("windows") -> "windows"
            osName.contains("mac") || osName.contains("darwin") -> "macos"
            osName.contains("linux") -> "linux"
            else -> throw IllegalStateException("Unsupported operating system: $osName")
        }
        
        val arch = when {
            osArch.contains("amd64") || osArch.contains("x86_64") -> "amd64"
            osArch.contains("aarch64") || osArch.contains("arm64") -> "arm64"
            else -> throw IllegalStateException("Unsupported architecture: $osArch")
        }
        
        return platform to arch
    }

    private fun getBinaryName(platform: String, arch: String): String {
        return when (platform) {
            "windows" -> "flux-windows-$arch.exe"
            "macos" -> "flux-macos-$arch"
            "linux" -> "flux-linux-$arch"
            else -> throw IllegalStateException("Unsupported platform: $platform")
        }
    }
    
    private fun downloadBinary(tag: String, binaryName: String): HttpResponse<InputStream>? {
        // Try primary repository first
        val primaryUrl = PRIMARY_BINARY_URL_TEMPLATE.format(tag, binaryName)
        val primaryResponse = tryDownloadBinary(primaryUrl)
        if (primaryResponse != null) return primaryResponse
        
        // Fall back to alternative repository
        val fallbackUrl = FALLBACK_BINARY_URL_TEMPLATE.format(tag, binaryName)
        return tryDownloadBinary(fallbackUrl)
    }
    
    private fun tryDownloadBinary(url: String): HttpResponse<InputStream>? {
        return try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
            if (response.statusCode() == 200) response else null
        } catch (_: Exception) {
            null
        }
    }

    private fun installVersion(tag: String) {
        val installDir = homeDir.resolve(".fluxzero")
        val binDir = installDir.resolve("bin")
        Files.createDirectories(binDir)

        // Detect current platform and architecture
        val (platform, arch) = detectPlatform()
        val binaryName = getBinaryName(platform, arch)
        
        // Inform user about download  
        System.err.println("Downloading fluxzero-cli $tag for $platform-$arch...")
        
        // Download native binary - try primary repository first, then fallback
        val binaryResponse = downloadBinary(tag, binaryName)
            ?: throw IllegalStateException("Failed to download native binary: $binaryName from both repositories")
        
        // Install as 'fz' in the bin directory
        val binaryPath = binDir.resolve("fz")
        binaryResponse.body().use { input: InputStream ->
            Files.copy(input, binaryPath, StandardCopyOption.REPLACE_EXISTING)
        }
        binaryPath.toFile().setExecutable(true)
        
        System.err.println("Installation complete!")
        
        // Clean up old installations if they exist
        cleanupLegacyInstallation()
    }
    
    private fun cleanupLegacyInstallation() {
        try {
            val legacyDir = homeDir.resolve(".flux")
            if (Files.exists(legacyDir)) {
                // Only remove if it contains our JAR to avoid removing user data
                val legacyJar = legacyDir.resolve("fluxzero-cli.jar")
                if (Files.exists(legacyJar)) {
                    Files.deleteIfExists(legacyJar)
                    Files.deleteIfExists(legacyDir.resolve("cli"))
                    // Only delete directory if empty
                    if (Files.list(legacyDir).use { it.count() } == 0L) {
                        Files.deleteIfExists(legacyDir)
                    }
                }
            }
        } catch (_: Exception) {
            // Ignore cleanup failures - not critical
        }
    }
}
