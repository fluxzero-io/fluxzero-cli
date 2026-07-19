package host.flux.dev

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class DevServerArtifactCacheTest {
    @TempDir
    lateinit var cacheDirectory: Path

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

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
