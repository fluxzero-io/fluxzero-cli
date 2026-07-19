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

internal fun defaultDevServerCacheDirectory(): Path =
    Path.of(System.getProperty("user.home"), ".fluxzero", "cache", "dev-server")

class DevServerArtifactCache(
    private val cacheDirectory: Path = defaultDevServerCacheDirectory(),
    private val downloader: (URI) -> ByteArray = ::download,
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
        val base = "$CENTRAL_REPOSITORY/${DEV_SERVER_GROUP_ID.replace('.', '/')}/$DEV_SERVER_ARTIFACT_ID/$version"
        val artifactName = "$DEV_SERVER_ARTIFACT_ID-$version-standalone.jar"
        val expected = parseChecksum(downloader(URI.create("$base/$artifactName.sha256")).decodeToString())
        val bytes = downloader(URI.create("$base/$artifactName"))
        val actual = sha256(bytes)
        check(actual.equals(expected, ignoreCase = true)) {
            "Checksum verification failed for Fluxzero dev server $version: expected $expected, got $actual."
        }
        writeAtomically(artifact, bytes)
        writeAtomically(checksum, "$actual\n".encodeToByteArray())
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

    private fun parseChecksum(value: String): String = CHECKSUM.find(value)?.value
        ?: error("Maven Central returned an invalid SHA-256 checksum")

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
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
        private const val CENTRAL_REPOSITORY = "https://repo.maven.apache.org/maven2"
        private val CHECKSUM = Regex("(?i)[a-f0-9]{64}")

        private fun download(uri: URI): ByteArray {
            val client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build()
            val request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30)).GET().build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofByteArray())
            check(response.statusCode() in 200..299) {
                "Maven Central request for $uri failed with HTTP ${response.statusCode()}"
            }
            return response.body()
        }
    }
}
