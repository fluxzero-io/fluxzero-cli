package host.flux.publishing

import java.net.URI
import java.nio.file.Path
import java.time.Clock
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.jar.Attributes

object PackageNameSupport {
    private val packageNamePattern = Regex("[a-z0-9]([-a-z0-9_.]*[a-z0-9])?")
    private val tagPattern = Regex("[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}")
    private val generatedTagTimestamp = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC)

    data class GitInfo(
        val branch: String?,
        val shortSha: String?,
        val dirty: Boolean
    )

    fun packageReference(host: String, packageName: String, version: String): String =
        packageReference(host, null, packageName, version)

    fun packageReference(host: String, teamId: String?, packageName: String, version: String): String {
        return "${packageRepository(host, teamId, packageName)}:$version"
    }

    fun packageRepository(host: String, teamId: String?, packageName: String): String {
        val registry = registryAuthority(host)
        val teamPath = teamId?.trim('/')?.takeIf { it.isNotBlank() }
        return if (teamPath == null) "$registry/$packageName" else "$registry/$teamPath/$packageName"
    }

    fun registryAuthority(registryReference: String): String {
        return registryReference.trim().removeSuffix("/").substringBefore("/")
    }

    fun isValidRegistryHost(host: String): Boolean {
        val normalized = host.trim()
        if (host != normalized || normalized.isBlank() || normalized != normalized.lowercase(Locale.ROOT)) {
            return false
        }
        if (normalized.contains('/') || normalized.contains('@') || normalized.contains('?') || normalized.contains('#')) {
            return false
        }
        return runCatching {
            val uri = URI.create("https://$normalized")
            uri.host?.isNotBlank() == true && uri.userInfo == null && uri.path.isBlank() &&
                uri.query == null && uri.fragment == null && (uri.port == -1 || uri.port in 1..65535)
        }.getOrDefault(false)
    }

    fun defaultPackageVersion(projectVersion: String): String {
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
                    "or pass --allow-dirty / -Dfluxzero.package.allowDirty=true to publish them intentionally."
            )
        }
    }

    fun automaticPackageVersion(projectDirectory: Path, clock: Clock = Clock.systemUTC(), allowDirty: Boolean = false): String =
        automaticPackageVersion(clock, gitInfo(projectDirectory), allowDirty = allowDirty)

    fun automaticPackageVersion(clock: Clock, gitInfo: GitInfo?, allowDirty: Boolean = false): String {
        val timestamp = generatedTagTimestamp.format(clock.instant())
        if (gitInfo == null) {
            throw IllegalStateException(
                "Cannot generate an automatic package version without a git commit. " +
                    "Set a package version explicitly or publish from a git checkout with at least one commit."
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
        return defaultPackageVersion(parts.joinToString("-"))
    }

    fun markDirtyPackageVersion(packageVersion: String, gitInfo: GitInfo?, allowDirty: Boolean): String {
        ensureCleanGitWorktree(gitInfo, allowDirty)
        if (gitInfo?.dirty != true || packageVersion.endsWith("-dirty", ignoreCase = true)) {
            return packageVersion
        }
        val suffix = "-dirty"
        val maxBaseLength = 128 - suffix.length
        val base = packageVersion
            .take(maxBaseLength)
            .trimEnd('.', '-')
        return "$base$suffix"
    }

    fun isValidPackageName(packageName: String): Boolean =
        packageName.length in 1..63 && packageNamePattern.matches(packageName)

    fun isValidTeamId(teamId: String): Boolean =
        isValidPackageName(teamId)

    fun isValidNamespace(namespace: String): Boolean =
        namespace.trim('/').split('/').all { isValidPackageName(it) }

    fun isValidImageRepository(image: String): Boolean {
        val normalized = image.trim()
        if (normalized.isBlank() || normalized != normalized.lowercase(Locale.ROOT) || normalized.contains(Regex("\\s"))) {
            return false
        }
        if (!normalized.contains("/") || normalized.contains("@")) {
            return false
        }
        if (normalized.substringAfterLast("/").contains(":")) {
            return false
        }
        val targetHost = registryAuthority(normalized)
        val path = normalized.substringAfter("/")
        return isValidRegistryHost(targetHost) && path.split("/").all { isValidPackageName(it) }
    }

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

    fun firstConfiguredValue(value: String?, environmentVariable: String): String? =
        value ?: if (System.getenv().containsKey(environmentVariable)) System.getenv(environmentVariable) ?: "" else null

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
