package host.flux.dev

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DevServerVersionResolverTest {
    @TempDir
    lateinit var projectDirectory: Path

    @Test
    fun `selects newest stable release within supported major`() {
        val metadata = metadata("0.9.9", "1.0.0", "1.4.2", "1.5.0-RC1", "2.0.0")

        assertEquals("1.4.2", DevServerVersionResolver.latestCompatible(metadata, 1))
    }

    @Test
    fun `uses cached compatible release when metadata is unavailable`() {
        val cache = projectDirectory.resolve("latest-major-1.txt")
        Files.createDirectories(cache.parent)
        Files.writeString(cache, "1.3.7")
        val messages = mutableListOf<String>()
        val resolver = DevServerVersionResolver({ error("offline") }, projectDirectory, messages::add)

        assertEquals("1.3.7", resolver.latestCompatible())
        assertTrue(messages.single().contains("cached version 1.3.7"))
    }

    @Test
    fun `fails clearly without metadata or compatible cache`() {
        val error = assertFailsWith<IllegalStateException> {
            DevServerVersionResolver({ error("offline") }, projectDirectory) { }.latestCompatible()
        }

        assertTrue(error.message.orEmpty().contains("--dev-server-version"))
    }

    private fun metadata(vararg versions: String): String = """
        <metadata>
          <groupId>io.fluxzero.tools</groupId>
          <artifactId>fluxzero-dev-server</artifactId>
          <versioning><versions>${versions.joinToString("") { "<version>$it</version>" }}</versions></versioning>
        </metadata>
    """.trimIndent()
}
