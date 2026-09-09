package host.flux.dev

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.ConnectException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DevServerArtifactCacheTest {
    @TempDir
    lateinit var cacheDirectory: Path

    @Test
    fun `uses explicit cache directory independently of native user home`() {
        assertEquals(
            cacheDirectory,
            defaultDevServerCacheDirectory(mapOf(DEV_SERVER_CACHE_ENVIRONMENT_VARIABLE to cacheDirectory.toString()))
        )
    }

    @Test
    fun `downloads verifies and reuses stable artifact`() {
        val bytes = "verified standalone jar".encodeToByteArray()
        var downloads = 0
        val cache = DevServerArtifactCache(cacheDirectory, { uri ->
            assertTrue(uri.toString().startsWith("https://packages.fluxzero.io/maven/io/fluxzero/tools/fluxzero-dev-server/1.2.3/"))
            downloads++
            if (uri.toString().endsWith(".sha256")) sha256(bytes).encodeToByteArray() else bytes
        }) { }

        val first = cache.resolve("1.2.3")
        val second = cache.resolve("1.2.3")

        assertEquals(first, second)
        assertEquals(2, downloads)
        assertEquals(bytes.toList(), Files.readAllBytes(first).toList())
    }

    @Test
    fun `verifies Maven SHA-1 when SHA-256 is absent and keeps a SHA-256 cache`() {
        val bytes = "Maven deployed standalone jar".encodeToByteArray()
        val requests = mutableListOf<String>()
        val cache = DevServerArtifactCache(cacheDirectory, { uri ->
            requests += uri.toString()
            when {
                uri.toString().endsWith(".sha256") -> throw ArtifactNotFoundException(uri)
                uri.toString().endsWith(".sha1") -> MessageDigest.getInstance("SHA-1").digest(bytes)
                    .joinToString("") { "%02x".format(it.toInt() and 0xff) }.encodeToByteArray()
                else -> bytes
            }
        }, { error("missing checksums should not be retried") }) { }

        val artifact = cache.resolve("1.2.3")
        assertEquals(3, requests.size)
        assertEquals(sha256(bytes), Files.readString(artifact.resolveSibling("${artifact.fileName}.sha256")).trim())
        assertEquals(artifact, cache.resolve("1.2.3"))
        assertEquals(3, requests.size, "a warm cache must not make repository requests")
    }

    @Test
    fun `does not downgrade checksum on a server failure or malformed SHA-256`() {
        for (malformed in listOf(false, true)) {
            val requests = mutableListOf<String>()
            val cache = DevServerArtifactCache(cacheDirectory, { uri ->
                requests += uri.toString()
                if (malformed) "invalid checksum".encodeToByteArray()
                else error("HTTP 500")
            }, { }) { }

            assertFailsWith<IllegalStateException> { cache.resolve("1.2.3") }
            assertEquals(if (malformed) 1 else 3, requests.size)
            assertTrue(requests.all { it.endsWith(".sha256") })
        }
    }

    @Test
    fun `rejects a mismatch in Maven SHA-1 without caching artifact`() {
        val cache = DevServerArtifactCache(cacheDirectory, { uri ->
            when {
                uri.toString().endsWith(".sha256") -> throw ArtifactNotFoundException(uri)
                uri.toString().endsWith(".sha1") -> "0".repeat(40).encodeToByteArray()
                else -> "corrupt".encodeToByteArray()
            }
        }, { }) { }

        assertFailsWith<ArtifactChecksumException> { cache.resolve("1.2.3") }
        assertFalse(Files.exists(cacheDirectory.resolve("1.2.3/fluxzero-dev-server-1.2.3-standalone.jar")))
    }

    @Test
    fun `rejects artifact with invalid checksum`() {
        val cache = DevServerArtifactCache(cacheDirectory, { uri ->
            if (uri.toString().endsWith(".sha256")) "0".repeat(64).encodeToByteArray()
            else "corrupt".encodeToByteArray()
        }) { }

        assertFailsWith<IllegalStateException> { cache.resolve("1.2.3") }

        assertFalse(Files.exists(cacheDirectory.resolve("1.2.3/fluxzero-dev-server-1.2.3-standalone.jar")))
    }

    @Test
    fun `retries first run downloads and reports failures without a message`() {
        var attempts = 0
        val messages = mutableListOf<String>()
        val cache = DevServerArtifactCache(
            cacheDirectory = cacheDirectory,
            downloader = {
                attempts++
                throw ConnectException()
            },
            retryWait = { },
            messageSink = messages::add
        )

        val error = assertFailsWith<IllegalStateException> { cache.resolve("1.2.3") }

        assertEquals(3, attempts)
        assertEquals(2, messages.count { it.startsWith("Retrying Fluxzero dev server 1.2.3") })
        assertTrue(error.message.orEmpty().contains("fluxzero-dev-server-1.2.3-standalone.jar.sha256"))
        assertTrue(error.message.orEmpty().contains("ConnectException"))
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
