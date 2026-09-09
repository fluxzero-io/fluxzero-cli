package host.flux.dev

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Duration

internal const val DEV_SERVER_CACHE_ENVIRONMENT_VARIABLE = "FLUXZERO_DEV_SERVER_CACHE"

internal fun defaultDevServerCacheDirectory(environment: Map<String, String> = System.getenv()): Path =
    environment[DEV_SERVER_CACHE_ENVIRONMENT_VARIABLE]?.takeIf(String::isNotBlank)?.let(Path::of)
        ?: Path.of(System.getProperty("user.home"), ".fluxzero", "cache", "dev-server")

class DevServerArtifactCache(
    private val cacheDirectory: Path = defaultDevServerCacheDirectory(),
    private val downloader: (URI) -> ByteArray = ::download,
    private val retryWait: (Long) -> Unit = Thread::sleep,
    private val messageSink: (String) -> Unit = { System.err.println(it) }
) {
    fun resolve(version: String): Path {
        require(StableVersion.parse(version) != null) {
            "Direct dev-server downloads require a stable semantic version, got '$version'."
        }
        val versionDirectory = cacheDirectory.resolve(version)
        Files.createDirectories(versionDirectory)
        val artifact = versionDirectory.resolve("$DEV_SERVER_ARTIFACT_ID-$version-standalone.jar")
        val checksum = versionDirectory.resolve("${artifact.fileName}.sha256")
        val lock = versionDirectory.resolve(".download.lock")
        FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            channel.lock().use {
                if (isValid(artifact, checksum)) return artifact
                Files.deleteIfExists(artifact)
                Files.deleteIfExists(checksum)
                messageSink("Downloading Fluxzero dev server $version...")
                downloadVerified(version, artifact, checksum)
            }
        }
        return artifact
    }

    internal fun isUsablePinnedArtifact(version: String, artifact: Path): Boolean {
        val expected = artifactPath(version).toAbsolutePath().normalize()
        val actual = artifact.toAbsolutePath().normalize()
        return actual != expected || isValid(actual, actual.resolveSibling("${actual.fileName}.sha256"))
    }

    private fun downloadVerified(version: String, artifact: Path, checksum: Path) {
        val base = "$FLUXZERO_PACKAGES_REPOSITORY/${DEV_SERVER_GROUP_ID.replace('.', '/')}/$DEV_SERVER_ARTIFACT_ID/$version"
        val artifactName = "$DEV_SERVER_ARTIFACT_ID-$version-standalone.jar"
        val (algorithm, expected) = try {
            "SHA-256" to parseChecksum(downloadWithRetry(version, URI.create("$base/$artifactName.sha256")).decodeToString())
        } catch (_: ArtifactNotFoundException) {
            // Maven 3 deploys SHA-1 by default; historical Central copies also carry SHA-256.
            "SHA-1" to parseChecksum(downloadWithRetry(version, URI.create("$base/$artifactName.sha1")).decodeToString(), 40)
        }
        val bytes = downloadWithRetry(version, URI.create("$base/$artifactName"))
        val actual = digest(bytes, algorithm)
        if (!actual.equals(expected, ignoreCase = true)) {
            throw ArtifactChecksumException("Checksum verification failed for Fluxzero dev server $version: expected $expected, got $actual.")
        }
        writeAtomically(artifact, bytes)
        writeAtomically(checksum, "${digest(bytes, "SHA-256")}\n".encodeToByteArray())
    }

    private fun downloadWithRetry(version: String, uri: URI): ByteArray {
        var lastFailure: Exception? = null
        repeat(DOWNLOAD_ATTEMPTS) { attempt ->
            try {
                return downloader(uri)
            } catch (e: ArtifactNotFoundException) {
                throw e
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw e
            } catch (e: Exception) {
                lastFailure = e
                if (attempt < DOWNLOAD_ATTEMPTS - 1) {
                    messageSink("Retrying Fluxzero dev server $version download (${attempt + 2}/$DOWNLOAD_ATTEMPTS)...")
                    retryWait(RETRY_DELAYS_MS[attempt])
                }
            }
        }
        val cause = requireNotNull(lastFailure)
        val detail = cause.message?.takeIf(String::isNotBlank) ?: cause.javaClass.simpleName
        throw IllegalStateException(
            "Could not download Fluxzero dev server $version from $uri after $DOWNLOAD_ATTEMPTS attempts: $detail",
            cause
        )
    }

    private fun isValid(artifact: Path, checksum: Path): Boolean = runCatching {
        Files.isRegularFile(artifact) && Files.isRegularFile(checksum) &&
            sha256(artifact).equals(
                parseChecksum(Files.readString(checksum)), ignoreCase = true
            )
    }.getOrDefault(false)

    private fun artifactPath(version: String): Path = cacheDirectory.resolve(version).resolve(
        "$DEV_SERVER_ARTIFACT_ID-$version-standalone.jar"
    )

    private fun parseChecksum(value: String, length: Int = 64): String = value.trim().substringBefore(' ')
        .takeIf { it.length == length && it.all { digit -> digit in '0'..'9' || digit.lowercaseChar() in 'a'..'f' } }
        ?: throw ArtifactChecksumException("Fluxzero Packages returned an invalid ${if (length == 64) "SHA-256" else "SHA-1"} checksum")

    private fun digest(bytes: ByteArray, algorithm: String): String = MessageDigest.getInstance(algorithm)
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun writeAtomically(target: Path, content: ByteArray) {
        val temporary = Files.createTempFile(target.parent, target.fileName.toString(), ".tmp")
        Files.write(temporary, content)
        try {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        private const val DOWNLOAD_ATTEMPTS = 3
        private val RETRY_DELAYS_MS = longArrayOf(250, 1_000)

        private fun download(uri: URI): ByteArray {
            val client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build()
            val request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30)).GET().build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofByteArray())
            if (response.statusCode() == 404) throw ArtifactNotFoundException(uri)
            check(response.statusCode() in 200..299) {
                "Fluxzero Packages request for $uri failed with HTTP ${response.statusCode()}"
            }
            return response.body()
        }
    }
}

internal class ArtifactNotFoundException(uri: URI) : IllegalStateException("Artifact not found at $uri (HTTP 404)")
internal class ArtifactChecksumException(message: String) : IllegalStateException(message)
