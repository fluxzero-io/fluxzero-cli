package host.flux.dev

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

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
}
