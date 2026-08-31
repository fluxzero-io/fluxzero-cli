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
