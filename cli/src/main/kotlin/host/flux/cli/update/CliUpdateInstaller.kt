package host.flux.cli.update

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import host.flux.cli.services.ManagedPackageInstallation
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

internal data class CliRelease(val tag: String, val assets: List<CliAsset>)
internal data class CliAsset(val name: String, val url: String, val digest: String)

internal class CliReleaseClient(
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NORMAL).build(),
) {
    fun latest(current: String): CliRelease? {
        val request = HttpRequest.newBuilder(URI.create("https://api.github.com/repos/fluxzero-io/fluxzero-cli/releases?per_page=100"))
            .timeout(Duration.ofSeconds(10)).header("Accept", "application/vnd.github+json").build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == 200) { "Release lookup returned HTTP ${response.statusCode()}" }
        return selectRelease(response.body(), current)
    }

    fun download(asset: CliAsset, destination: Path) {
        require(asset.url.startsWith("https://github.com/fluxzero-io/fluxzero-cli/releases/download/")) { "Unexpected release asset URL" }
        val request = HttpRequest.newBuilder(URI.create(asset.url)).timeout(Duration.ofMinutes(5)).build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofFile(destination))
        check(response.statusCode() == 200) { "Download returned HTTP ${response.statusCode()}" }
    }
}

internal fun selectRelease(json: String, current: String): CliRelease? {
    val installed = CliVersion.parse(current) ?: return null
    return ObjectMapper().readTree(json).filter { !it.path("draft").asBoolean(true) && !it.path("prerelease").asBoolean(true) }
        .mapNotNull { release ->
            val tag = release.path("tag_name").asText()
            val version = CliVersion.parse(tag) ?: return@mapNotNull null
            if (version.major != installed.major || version <= installed) return@mapNotNull null
            version to CliRelease(tag, release.path("assets").map {
                CliAsset(it.path("name").asText(), it.path("browser_download_url").asText(), it.path("digest").asText())
            })
        }.maxByOrNull { it.first }?.second
}

internal class CliUpdateInstaller(
    private val installation: CliInstallation = CliInstallation.current(),
    private val releases: (String) -> CliRelease? = CliReleaseClient()::latest,
    private val download: (CliAsset, Path) -> Unit = CliReleaseClient()::download,
    private val command: (List<String>, Map<String, String>) -> String = ::runUpdateCommand,
    private val allowed: () -> Boolean = { CliUpdateSettings().enabled() == true },
    private val environment: Map<String, String> = System.getenv(),
    private val windows: Boolean = isWindows(),
    private val nativeAsset: String = nativeAssetName(),
) {
    fun update(current: String): String {
        val running = CliVersion.parse(current) ?: return "Development and prerelease versions are not updated automatically"
        val release = releases(current) ?: return "No newer stable CLI release in major ${running.major}"
        val target = CliVersion.parse(release.tag)
        check(target != null && target.major == running.major && target > running) { "Incompatible CLI update" }
        if (!allowed()) return "Automatic updates disabled"
        when (ManagedPackageInstallation.detect(installation.path)) {
            ManagedPackageInstallation.HOMEBREW -> return updateHomebrew(release, current)
            ManagedPackageInstallation.WINGET -> return updateWinget(release)
            null -> updateFiles(release)
        }
        return "Fluxzero CLI updated to ${release.tag}; running processes keep their current version"
    }

    private fun updateHomebrew(release: CliRelease, current: String): String {
        if (environment.keys.any { it in setOf("HOMEBREW_NO_AUTO_UPDATE", "HOMEBREW_NO_INSTALL_UPGRADE", "HOMEBREW_BUNDLE_NO_UPGRADE") }) {
            return "Homebrew update opt-out preserved"
        }
        val env = mapOf("NONINTERACTIVE" to "1", "HOMEBREW_NO_AUTO_UPDATE" to "1", "HOMEBREW_NO_INSTALL_CLEANUP" to "1",
            "HOMEBREW_NO_INSTALLED_DEPENDENTS_CHECK" to "1", "HOMEBREW_NO_ENV_HINTS" to "1")
        val formula = "fluxzero-io/tap/fluxzero"
        fun info(): JsonNode = ObjectMapper().readTree(command(listOf("brew", "info", "--json=v2", formula), env))
            .path("formulae").single { it.path("full_name").asText() == formula }
        var details = info()
        if (details.path("pinned").asBoolean()) return "Homebrew pin preserved"
        command(listOf("brew", "update"), env)
        details = info()
        if (details.path("pinned").asBoolean()) return "Homebrew pin preserved"
        // Homebrew has no exact-version upgrade flag. Freeze its metadata refresh between this check and upgrade.
        if (CliVersion.parse(details.path("versions").path("stable").asText()) != CliVersion.parse(release.tag)) {
            return "Homebrew does not currently offer the selected same-major release; update deferred"
        }
        val installedVersions = details.path("installed").mapNotNull { CliVersion.parse(it.path("version").asText()) }
        if (installedVersions.any { it >= CliVersion.parse(release.tag)!! }) return "Homebrew installation is already current"
        check(installedVersions.contains(CliVersion.parse(current))) { "Homebrew installation changed; update deferred" }
        if (!allowed()) return "Automatic updates disabled"
        command(listOf("brew", "upgrade", "--formula", formula), env)
        val prefix = command(listOf("brew", "--prefix", formula), env).trim()
        check(CliVersion.parse(command(listOf(Path.of(prefix, "bin", "fz").toString(), "--version"), env).trim()) == CliVersion.parse(release.tag)) {
            "Homebrew update could not be verified"
        }
        return "Fluxzero CLI updated to ${release.tag} through Homebrew"
    }

    private fun updateWinget(release: CliRelease): String {
        val identity = listOf("--exact", "--id", "Fluxzero.FluxzeroCLI")
        val pins = command(listOf("winget", "pin", "list") + identity + "--disable-interactivity", emptyMap())
        if (pins.contains("Fluxzero.FluxzeroCLI", ignoreCase = true)) return "WinGet pin preserved"
        if (!allowed()) return "Automatic updates disabled"
        command(listOf("winget", "upgrade") + identity + listOf("--version", release.tag.removePrefix("v"),
            "--silent", "--disable-interactivity", "--accept-source-agreements", "--accept-package-agreements"), emptyMap())
        check(CliVersion.parse(command(listOf("fz.exe", "--version"), emptyMap()).trim()) == CliVersion.parse(release.tag)) {
            "WinGet update could not be verified"
        }
        return "Fluxzero CLI updated to ${release.tag} through WinGet"
    }

    private fun updateFiles(release: CliRelease) {
        val target = installation.path
        check(Files.isWritable(target.parent)) { "CLI installation is not writable; use fz upgrade with its owning installer" }
        val assetName = if (installation.jar) "fluxzero-cli.jar" else nativeAsset
        val asset = release.assets.singleOrNull { it.name == assetName } ?: error("Release is missing $assetName")
        check(Regex("sha256:[0-9a-fA-F]{64}").matches(asset.digest)) { "Release asset has no valid SHA-256 digest" }
        val originalDigest = sha256(target)
        val stage = Files.createTempDirectory(target.parent, ".fz-update-")
        try {
            val archive = stage.resolve(assetName)
            download(asset, archive)
            check(sha256(archive).equals(asset.digest.substringAfter(':'), true)) {
                "Release asset checksum mismatch"
            }
            val binary = if (windows && !installation.jar) extractWindowsPackage(archive, stage) else archive
            check(binary.toFile().setExecutable(true) || windows) { "Cannot make the CLI update executable" }
            val verify = if (installation.jar) installation.command().dropLast(1) + binary.toString() else listOf(binary.toString())
            check(CliVersion.parse(command(verify + "--version", emptyMap()).trim()) == CliVersion.parse(release.tag)) {
                "Downloaded CLI version does not match the selected release"
            }
            if (!allowed()) error("Automatic updates disabled")
            check(sha256(target) == originalDigest) { "CLI installation changed during download; update deferred" }
            val replacements = linkedMapOf(target to binary)
            val names = if (windows) setOf("fz.exe", "fluxzero.exe") else setOf("fz", "fluxzero")
            if (!installation.jar && target.fileName.toString() in names) {
                names.filter { it != target.fileName.toString() }.forEach { name ->
                    val alias = target.resolveSibling(name)
                    // Preserve symlinks and independently maintained aliases.
                    if (Files.isRegularFile(alias) && !Files.isSymbolicLink(alias) && Files.mismatch(target, alias) == -1L) {
                        replacements[alias] = binary
                    }
                }
            }
            if (windows && !installation.jar) Files.list(stage).use { paths ->
                paths.filter { it.fileName.toString().endsWith(".dll", true) }.forEach { replacements[target.resolveSibling(it.fileName)] = it }
            }
            replaceInstallation(replacements)
        } finally {
            Files.walk(stage).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { runCatching { Files.deleteIfExists(it) } } }
        }
    }
}

private fun sha256(path: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).use { input ->
        val buffer = ByteArray(8192)
        while (true) {
            val size = input.read(buffer)
            if (size < 0) break
            digest.update(buffer, 0, size)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

internal fun extractWindowsPackage(archive: Path, stage: Path): Path {
    ZipInputStream(Files.newInputStream(archive)).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            require(!entry.isDirectory && !entry.name.contains('/') && !entry.name.contains('\\') &&
                (entry.name in setOf("fz.exe", "fluxzero.exe") || entry.name.matches(Regex("[A-Za-z0-9_.-]+\\.dll", RegexOption.IGNORE_CASE)))) {
                "Unexpected Windows package entry"
            }
            val file = stage.resolve(entry.name)
            check(!Files.exists(file)) { "Duplicate Windows package entry" }
            Files.copy(zip, file)
        }
    }
    check(Files.isRegularFile(stage.resolve("fz.exe"))) { "Windows package is missing fz.exe" }
    return stage.resolve("fz.exe")
}

/** Stage beside each destination, rename rather than truncate running executables, and roll back partial swaps. */
internal fun replaceInstallation(replacements: Map<Path, Path>) {
    val staged = linkedMapOf<Path, Path>()
    val backups = linkedMapOf<Path, Path?>()
    try {
        replacements.forEach { (target, source) ->
            val temporary = Files.createTempFile(target.parent, ".fz-new-", if (isWindows()) ".exe" else ".tmp")
            staged[target] = temporary
            Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
        }
        staged.forEach { (target, temporary) ->
            val backup = if (Files.exists(target)) {
                Files.createTempFile(target.parent, ".fz-old-", ".tmp").also {
                    Files.delete(it)
                    if (isWindows()) Files.move(target, it, StandardCopyOption.ATOMIC_MOVE)
                    else Files.copy(target, it, StandardCopyOption.COPY_ATTRIBUTES)
                }
            } else null
            backups[target] = backup
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }
    } catch (failure: Exception) {
        backups.entries.reversed().forEach { (target, backup) ->
            runCatching {
                if (backup != null) Files.move(backup, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                else Files.deleteIfExists(target)
            }.exceptionOrNull()?.let(failure::addSuppressed)
        }
        throw failure
    } finally {
        staged.values.forEach { runCatching { Files.deleteIfExists(it) } }
    }
    // Windows may retain a renamed executable or DLL until its existing process exits.
    backups.values.filterNotNull().forEach { runCatching { Files.deleteIfExists(it) } }
}

internal fun nativeAssetName(): String {
    val platform = when {
        isWindows() -> "windows"
        System.getProperty("os.name").lowercase().let { "mac" in it || "darwin" in it } -> "macos"
        else -> "linux"
    }
    val arch = when (System.getProperty("os.arch").lowercase()) {
        "aarch64", "arm64" -> "arm64"
        "amd64", "x86_64" -> "amd64"
        else -> "unsupported"
    }
    return "flux-$platform-$arch" + if (isWindows()) ".zip" else ""
}

internal fun runUpdateCommand(command: List<String>, environment: Map<String, String>): String {
    val output = Files.createTempFile("fluxzero-update-command-", ".log")
    val errors = Files.createTempFile("fluxzero-update-command-", ".err")
    try {
        val process = ProcessBuilder(command).redirectInput(ProcessBuilder.Redirect.from(Path.of(if (isWindows()) "NUL" else "/dev/null").toFile()))
            .redirectOutput(output.toFile()).redirectError(errors.toFile()).apply { environment().putAll(environment) }.start()
        if (!process.waitFor(10, TimeUnit.MINUTES)) {
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
            error("Update command timed out: ${command.first()}")
        }
        check(process.exitValue() == 0) {
            "Update command failed: ${command.first()} (exit ${process.exitValue()}): ${Files.readString(errors).takeLast(500)}"
        }
        return Files.readString(output)
    } finally {
        Files.deleteIfExists(output)
        Files.deleteIfExists(errors)
    }
}
