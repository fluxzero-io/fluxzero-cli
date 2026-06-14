package host.flux.publishing

import java.net.URI
import java.nio.file.Path
import java.time.Clock
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.jar.Attributes

object ImageNameSupport {
    const val DEFAULT_REGISTRY_HOST = "registry.fluxzero.io"

    private val imageNamePattern = Regex("[a-z0-9]([-a-z0-9_.]*[a-z0-9])?")
    private val tagPattern = Regex("[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}")
    private val generatedTagTimestamp = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC)

    data class GitInfo(
        val branch: String?,
        val shortSha: String?,
        val dirty: Boolean
    )

    fun imageReference(registryHost: String, imageName: String, version: String): String {
        val registry = registryAuthority(registryHost)
        return "$registry/$imageName:$version"
    }

    fun registryAuthority(registryHost: String): String {
        val trimmed = registryHost.trim().removeSuffix("/")
        val lower = trimmed.lowercase(Locale.ROOT)
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            val uri = URI.create(trimmed)
            return if (uri.port >= 0) "${uri.host}:${uri.port}" else uri.host
        }
        return trimmed.substringBefore("/")
    }

    fun isPlainHttpRegistryHost(registryHost: String): Boolean =
        registryHost.trim().startsWith("http://", ignoreCase = true)

    fun defaultImageVersion(projectVersion: String): String {
        if (isValidTag(projectVersion)) {
            return projectVersion
        }
        val sanitized = projectVersion
            .replace(Regex("[^A-Za-z0-9_.-]"), "-")
            .take(127)
            .trim('.', '-')
        return if (sanitized.isBlank() || !sanitized.first().isLetterOrDigit() && sanitized.first() != '_') {
            "v$sanitized".take(128).trimEnd('.', '-')
        } else {
            sanitized
        }
    }

    fun ensureCleanGitWorktree(projectDirectory: Path, allowDirty: Boolean) {
        ensureCleanGitWorktree(gitInfo(projectDirectory), allowDirty)
    }

    fun ensureCleanGitWorktree(gitInfo: GitInfo?, allowDirty: Boolean) {
        if (gitInfo?.dirty == true && !allowDirty) {
            throw IllegalStateException(
                "Refusing to publish from a dirty git worktree. Commit or stash local changes, " +
                    "or pass --allow-dirty / -Dfluxzero.image.allowDirty=true to publish them intentionally."
            )
        }
    }

    fun automaticImageVersion(projectDirectory: Path, clock: Clock = Clock.systemUTC(), allowDirty: Boolean = false): String =
        automaticImageVersion(clock, gitInfo(projectDirectory), allowDirty = allowDirty)

    fun automaticImageVersion(clock: Clock, gitInfo: GitInfo?, allowDirty: Boolean = false): String {
        val timestamp = generatedTagTimestamp.format(clock.instant())
        if (gitInfo == null) {
            throw IllegalStateException(
                "Cannot generate an automatic image tag without a git commit. " +
                    "Set an image tag explicitly or publish from a git checkout with at least one commit."
            )
        }
        ensureCleanGitWorktree(gitInfo, allowDirty)

        val branch = gitInfo.branch
            ?.takeIf { it != "HEAD" }
            ?.let { tagPart(it, maxLength = 48) }
            ?: "detached"
        val shortSha = gitInfo.shortSha?.let { tagPart(it, maxLength = 12) }
        val parts = buildList {
            add("dev")
            add(branch)
            add(timestamp)
            shortSha?.let(::add)
            if (gitInfo.dirty) {
                add("dirty")
            }
        }
        return defaultImageVersion(parts.joinToString("-"))
    }

    fun markDirtyImageVersion(imageVersion: String, gitInfo: GitInfo?, allowDirty: Boolean): String {
        ensureCleanGitWorktree(gitInfo, allowDirty)
        if (gitInfo?.dirty != true || imageVersion.endsWith("-dirty", ignoreCase = true)) {
            return imageVersion
        }
        val suffix = "-dirty"
        val maxBaseLength = 128 - suffix.length
        val base = imageVersion
            .take(maxBaseLength)
            .trimEnd('.', '-')
        return "$base$suffix"
    }

    fun isValidImageName(imageName: String): Boolean =
        imageName.length in 1..63 && imageNamePattern.matches(imageName)

    fun isValidTag(version: String): Boolean = tagPattern.matches(version)

    fun mainClassFromManifest(attributes: Attributes?): String? {
        if (attributes == null) {
            return null
        }
        return firstNonBlank(attributes.getValue("Start-Class"), attributes.getValue("Main-Class"))
    }

    fun firstConfigured(value: String?, environmentVariable: String): String? =
        value?.takeIf { it.isNotBlank() }
            ?: System.getenv(environmentVariable)?.takeIf { it.isNotBlank() }

    fun gitInfo(projectDirectory: Path): GitInfo? {
        val branch = git(projectDirectory, "rev-parse", "--abbrev-ref", "HEAD")
        val shortSha = git(projectDirectory, "rev-parse", "--short=12", "HEAD")
        if (branch == null && shortSha == null) {
            return null
        }
        val dirty = git(projectDirectory, "status", "--porcelain")?.isNotBlank() == true
        return GitInfo(branch = branch, shortSha = shortSha, dirty = dirty)
    }

    private fun git(projectDirectory: Path, vararg args: String): String? =
        runCatching {
            val command = listOf("git", "-C", projectDirectory.toAbsolutePath().toString()) + args
            val process = ProcessBuilder(command)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText().trim() }
            if (process.waitFor() == 0) output.takeIf { it.isNotBlank() } else null
        }.getOrNull()

    private fun tagPart(value: String, maxLength: Int): String {
        val sanitized = value
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9_.-]"), "-")
            .replace(Regex("-+"), "-")
            .trim('.', '-', '_')
            .take(maxLength)
            .trim('.', '-', '_')
        return sanitized.ifBlank { "unknown" }
    }

    private fun firstNonBlank(vararg values: String?): String? =
        values.firstNotNullOfOrNull { value -> value?.trim()?.takeIf { it.isNotBlank() } }
}
