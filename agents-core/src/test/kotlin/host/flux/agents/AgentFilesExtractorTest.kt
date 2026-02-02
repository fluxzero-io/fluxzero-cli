package host.flux.agents

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentFilesExtractorTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `extracts files from zip archive`() {
        val zipData = createTestZip(
            "AGENTS.md" to "# Agents",
            "CLAUDE.md" to "# Claude",
            ".aiassistant/rules/guidelines.md" to "Guidelines content"
        )

        val extractedFiles = AgentFilesExtractor.extract(zipData, tempDir)

        assertEquals(3, extractedFiles.size)
        assertTrue(Files.exists(tempDir.resolve("AGENTS.md")))
        assertTrue(Files.exists(tempDir.resolve("CLAUDE.md")))
        assertTrue(Files.exists(tempDir.resolve(".aiassistant/rules/guidelines.md")))
        assertEquals("# Agents", Files.readString(tempDir.resolve("AGENTS.md")))
    }

    @Test
    fun `creates nested directories`() {
        val zipData = createTestZip(
            ".aiassistant/rules/nested/deep/file.md" to "Deep content"
        )

        AgentFilesExtractor.extract(zipData, tempDir)

        assertTrue(Files.exists(tempDir.resolve(".aiassistant/rules/nested/deep/file.md")))
        assertEquals("Deep content", Files.readString(tempDir.resolve(".aiassistant/rules/nested/deep/file.md")))
    }

    @Test
    fun `overwrites existing files`() {
        // Create existing file
        Files.writeString(tempDir.resolve("AGENTS.md"), "Old content")

        val zipData = createTestZip(
            "AGENTS.md" to "New content"
        )

        AgentFilesExtractor.extract(zipData, tempDir)

        assertEquals("New content", Files.readString(tempDir.resolve("AGENTS.md")))
    }

    @Test
    fun `cleans existing agent files`() {
        // Create existing agent files
        Files.writeString(tempDir.resolve("AGENTS.md"), "Old agents")
        Files.writeString(tempDir.resolve("CLAUDE.md"), "Old claude")

        val aiAssistantDir = tempDir.resolve(".aiassistant/rules")
        Files.createDirectories(aiAssistantDir)
        Files.writeString(aiAssistantDir.resolve("guidelines.md"), "Old guidelines")

        val junieDir = tempDir.resolve(".junie")
        Files.createDirectories(junieDir)
        Files.writeString(junieDir.resolve("config.md"), "Old junie")

        val removedFiles = AgentFilesExtractor.cleanExistingFiles(tempDir)

        assertEquals(4, removedFiles.size)
        assertFalse(Files.exists(tempDir.resolve("AGENTS.md")))
        assertFalse(Files.exists(tempDir.resolve("CLAUDE.md")))
        assertFalse(Files.exists(tempDir.resolve(".aiassistant")))
        assertFalse(Files.exists(tempDir.resolve(".junie")))
    }

    @Test
    fun `clean handles missing files gracefully`() {
        // No existing files to clean

        val removedFiles = AgentFilesExtractor.cleanExistingFiles(tempDir)

        assertEquals(0, removedFiles.size)
    }

    @Test
    fun `does not extract files outside project directory`() {
        // Create a malicious zip with path traversal
        val zipData = createTestZip(
            "../outside.txt" to "Malicious content"
        )

        val extractedFiles = AgentFilesExtractor.extract(zipData, tempDir)

        // The malicious entry should be skipped
        assertEquals(0, extractedFiles.size)
        assertFalse(Files.exists(tempDir.parent.resolve("outside.txt")))
    }

    @Test
    fun `handles directory entries in zip`() {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            // Add directory entry
            zos.putNextEntry(ZipEntry(".aiassistant/rules/"))
            zos.closeEntry()

            // Add file in directory
            zos.putNextEntry(ZipEntry(".aiassistant/rules/file.md"))
            zos.write("Content".toByteArray())
            zos.closeEntry()
        }

        val extractedFiles = AgentFilesExtractor.extract(baos.toByteArray(), tempDir)

        assertEquals(1, extractedFiles.size) // Only files, not directories
        assertTrue(Files.exists(tempDir.resolve(".aiassistant/rules/file.md")))
    }

    private fun createTestZip(vararg entries: Pair<String, String>): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            for ((name, content) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(content.toByteArray())
                zos.closeEntry()
            }
        }
        return baos.toByteArray()
    }
}
