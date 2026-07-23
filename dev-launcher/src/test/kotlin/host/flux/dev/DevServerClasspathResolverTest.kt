package host.flux.dev

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.assertTrue

class DevServerClasspathResolverTest {
    @TempDir
    lateinit var projectDirectory: Path

    @Test
    fun `successful resolution separates launcher and server output`() {
        Files.writeString(projectDirectory.resolve("pom.xml"), "<project/>")
        val dependency = Files.createFile(projectDirectory.resolve("dev-server.jar"))
        val messages = mutableListOf<String>()
        val executor = CommandExecutor { command, _, _ ->
            val output = command.first { it.startsWith("-Dmdep.outputFile=") }.substringAfter('=')
            Files.writeString(Path.of(output), dependency.toString())
            0
        }
        val resolver = DevServerClasspathResolver(executor, messages::add)

        assertEquals(dependency.toString(), resolver.resolve(projectDirectory, "0-SNAPSHOT"))
        assertEquals(listOf("Resolving Fluxzero dev server 0-SNAPSHOT...", ""), messages)

        assertEquals(dependency.toString(), resolver.resolve(projectDirectory, "0-SNAPSHOT", reuseSnapshotCache = true))
        assertEquals(2, messages.size, "a cached resolution should not add output or whitespace")
    }

    @Test
    fun `stable release is downloaded directly and pins project launcher state`() {
        val artifactBytes = "standalone dev server".encodeToByteArray()
        val downloads = mutableListOf<String>()
        val artifacts = DevServerArtifactCache(projectDirectory.resolve("global-cache"), { uri ->
            downloads += uri.toString()
            if (uri.toString().endsWith(".sha256")) sha256(artifactBytes).encodeToByteArray() else artifactBytes
        }) { }
        val resolver = DevServerClasspathResolver(CommandExecutor { _, _, _ -> error("must not use build tool") },
                                                  artifacts) { }

        val classpath = resolver.resolve(projectDirectory, "1.2.3")

        assertEquals(2, downloads.size)
        assertEquals("1.2.3", resolver.resolvedVersion(projectDirectory))
        assertEquals(classpath, Files.readString(projectDirectory.resolve(".fluxzero/dev/launcher/classpath.txt")))
        assertEquals("standalone dev server", Files.readString(Path.of(classpath)))

        Files.writeString(Path.of(classpath), "corrupt")
        assertEquals(classpath, resolver.resolve(projectDirectory, "1.2.3"))
        assertEquals(4, downloads.size, "a corrupt managed artifact should be downloaded and verified again")
        assertEquals("standalone dev server", Files.readString(Path.of(classpath)))
    }

    @Test
    fun `stable release falls back to Maven when native download fails without a message`() {
        Files.writeString(projectDirectory.resolve("pom.xml"), "<project/>")
        val dependency = Files.writeString(projectDirectory.resolve("dev-server.jar"), "resolved by Maven")
        val messages = mutableListOf<String>()
        val artifacts = DevServerArtifactCache(
            cacheDirectory = projectDirectory.resolve("global-cache"),
            downloader = { _: URI -> throw NullPointerException() },
            retryWait = { },
            messageSink = messages::add
        )
        val executor = CommandExecutor { command, _, _ ->
            val output = command.first { it.startsWith("-Dmdep.outputFile=") }.substringAfter('=')
            Files.writeString(Path.of(output), dependency.toString())
            0
        }
        val resolver = DevServerClasspathResolver(executor, artifacts, messages::add)

        val classpath = resolver.resolve(projectDirectory, "1.2.3")

        assertEquals(dependency.toAbsolutePath().normalize().toString(), classpath)
        assertEquals("1.2.3", resolver.resolvedVersion(projectDirectory))
        assertTrue(messages.any {
            it.contains("Direct Fluxzero dev server 1.2.3 download failed") &&
                it.contains("NullPointerException") &&
                it.contains("Retrying through Maven")
        })
        assertTrue(messages.contains("Resolving Fluxzero dev server 1.2.3..."))
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
