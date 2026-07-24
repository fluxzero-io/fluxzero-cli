package host.flux.templates.services

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ZipTemplateExtractorTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `extracts ordinary nested archive entries`() {
        val target = tempDir.resolve("target")

        ZipTemplateExtractor.extract(zip("src/main/App.java" to "class App {}"), target)

        assertEquals("class App {}", Files.readString(target.resolve("src/main/App.java")))
    }

    @Test
    fun `accepts a conventional archive root directory marker`() {
        val target = tempDir.resolve("target")

        ZipTemplateExtractor.extract(zip("./" to "", "./src/App.java" to "class App {}"), target)

        assertEquals("class App {}", Files.readString(target.resolve("src/App.java")))
    }

    @Test
    fun `rejects archive entries that escape the target root`() {
        val target = tempDir.resolve("target")
        val escaped = tempDir.resolve("escaped.txt")

        val error = assertThrows(IllegalArgumentException::class.java) {
            ZipTemplateExtractor.extract(zip("../escaped.txt" to "unsafe"), target)
        }

        assertEquals("Template archive entry escapes the target directory: ../escaped.txt", error.message)
        assertFalse(Files.exists(escaped))
    }

    @Test
    fun `rejects writes through an existing symbolic link`() {
        val target = tempDir.resolve("target")
        val external = tempDir.resolve("external")
        Files.createDirectories(target)
        Files.createDirectories(external)
        try {
            Files.createSymbolicLink(target.resolve("linked"), external)
        } catch (_: Exception) {
            assumeTrue(false, "symbolic links are unavailable on this test platform")
        }

        assertThrows(IllegalArgumentException::class.java) {
            ZipTemplateExtractor.extract(zip("linked/escaped.txt" to "unsafe"), target)
        }
        assertFalse(Files.exists(external.resolve("escaped.txt")))
    }

    private fun zip(vararg entries: Pair<String, String>): ByteArrayInputStream {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return ByteArrayInputStream(bytes.toByteArray())
    }
}
