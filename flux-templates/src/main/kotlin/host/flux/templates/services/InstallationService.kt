package host.flux.templates.services

import host.flux.templates.models.InstallResult
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

interface InstallationService {
    fun install(): InstallResult
    fun getCurrentVersion(): String?
}

internal const val PRIMARY_LATEST_API_URL =
    "https://api.github.com/repos/flux-capacitor-io/flux-cli/releases/latest"
internal const val FALLBACK_LATEST_API_URL =
    "https://api.github.com/repos/fluxzero/cli/releases/latest"
internal const val PRIMARY_BINARY_URL_TEMPLATE =
    "https://github.com/flux-capacitor-io/flux-cli/releases/download/%s/%s"
internal const val FALLBACK_BINARY_URL_TEMPLATE =
    "https://github.com/fluxzero/cli/releases/download/%s/%s"

open class DefaultInstallationService(
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.ALWAYS)
        .build(),
    private val homeDir: Path = Paths.get(System.getProperty("user.home")),
    private val updateService: UpdateService = UpdateService
) : InstallationService {

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
            updateService.isNewer(current, latest) -> {
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

    override fun getCurrentVersion(): String? {
        return try {
            // Try to get version from the currently running JAR/native binary
            this::class.java.`package`.implementationVersion?.let { version ->
                if (version != "dev") version else null
            } ?: run {
                // Fallback to checking filesystem for existing installation
                val binPath = homeDir.resolve(".fluxzero/bin/fz")
                if (Files.exists(binPath)) {
                    // Try to extract version from binary (simplified approach)
                    "unknown"
                } else {
                    val legacyPath = homeDir.resolve(".flux")
                    if (Files.exists(legacyPath)) "unknown" else null
                }
            }
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

    private fun cleanupLegacyInstallation() {
        try {
            val legacyDir = homeDir.resolve(".flux")
            if (Files.exists(legacyDir)) {
                Files.walk(legacyDir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(Files::delete)
            }
        } catch (_: Exception) {
            // Ignore cleanup failures
        }
    }
}