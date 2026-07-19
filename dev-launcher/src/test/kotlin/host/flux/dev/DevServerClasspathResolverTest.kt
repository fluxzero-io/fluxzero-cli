package host.flux.dev

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

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

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
