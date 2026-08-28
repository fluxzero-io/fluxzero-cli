package host.flux.templates.services

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path

class InPlaceScaffoldPublisherTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `OS target lock can be reacquired after its previous owner closes`() {
        val target = tempDir.resolve("project")

        InPlaceScaffoldPublisher.acquireTargetLock(target).close()
        InPlaceScaffoldPublisher.acquireTargetLock(target).close()

        assertTrue(Files.isRegularFile(tempDir.resolve(".project.fluxzero-scaffold.lock")))
    }

    @Test
    fun `publish never overwrites a late collision and rolls back earlier moves`() {
        val staging = Files.createDirectory(tempDir.resolve("staging"))
        val target = Files.createDirectory(tempDir.resolve("target"))
        Files.writeString(staging.resolve("a.txt"), "generated-a")
        Files.writeString(staging.resolve("b.txt"), "generated-b")
        var moves = 0

        assertThrows(FileAlreadyExistsException::class.java) {
            InPlaceScaffoldPublisher.publish(staging, target) { source, destination ->
                moves++
                if (moves == 2) Files.writeString(destination, "external-b")
                Files.move(source, destination)
            }
        }

        assertEquals("generated-a", Files.readString(staging.resolve("a.txt")))
        assertEquals("generated-b", Files.readString(staging.resolve("b.txt")))
        assertFalse(Files.exists(target.resolve("a.txt")))
        assertEquals("external-b", Files.readString(target.resolve("b.txt")))
    }

    @Test
    fun `publishes ordinary content before Fluxzero config settings and the root build descriptor`() {
        val staging = Files.createDirectory(tempDir.resolve("staging"))
        val target = Files.createDirectory(tempDir.resolve("target"))
        Files.createDirectories(target.resolve(".fluxzero/dev"))
        Files.createDirectories(staging.resolve(".fluxzero"))
        Files.writeString(staging.resolve("ordinary.txt"), "ordinary")
        Files.writeString(staging.resolve(".fluxzero/dev.yaml"), "apps: []")
        Files.writeString(staging.resolve("settings.gradle"), "rootProject.name = 'example'")
        Files.writeString(staging.resolve("pom.xml"), "<project/>")
        val order = mutableListOf<String>()

        InPlaceScaffoldPublisher.publish(staging, target) { source, destination ->
            order.add(target.relativize(destination).toString().replace('\\', '/'))
            Files.move(source, destination)
        }

        assertEquals(
            listOf("ordinary.txt", ".fluxzero/dev.yaml", "settings.gradle", "pom.xml"),
            order
        )
    }
}
