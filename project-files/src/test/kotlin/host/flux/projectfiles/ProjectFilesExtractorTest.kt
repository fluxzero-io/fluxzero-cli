package host.flux.projectfiles

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

class ProjectFilesExtractorTest {

    @TempDir
    lateinit var tempDir: Path

    private val fluxzeroDir: Path
        get() = tempDir.resolve(".fluxzero")

    private val agentsDir: Path
        get() = fluxzeroDir.resolve("agents")

    @Test
    fun `extracts files from zip archive with java prefix`() {
        val zipData = createTestZip(
            "java/AGENTS.md" to "# Agents",
            "java/CLAUDE.md" to "# Claude",
            "java/.aiassistant/rules/guidelines.md" to "Guidelines content"
        )

        val extractedFiles = ProjectFilesExtractor.extract(zipData, tempDir)

        assertEquals(3, extractedFiles.size)
        assertTrue(Files.exists(agentsDir.resolve("AGENTS.md")))
        assertTrue(Files.exists(agentsDir.resolve("CLAUDE.md")))
        assertTrue(Files.exists(agentsDir.resolve(".aiassistant/rules/guidelines.md")))
        assertEquals("# Agents", Files.readString(agentsDir.resolve("AGENTS.md")))
    }

    @Test
    fun `extracts files from zip archive with kotlin prefix`() {
        val zipData = createTestZip(
            "kotlin/AGENTS.md" to "# Kotlin Agents",
            "kotlin/.aiassistant/instructions.md" to "Instructions"
        )

        val extractedFiles = ProjectFilesExtractor.extract(zipData, tempDir)

        assertEquals(2, extractedFiles.size)
        assertTrue(Files.exists(agentsDir.resolve("AGENTS.md")))
        assertTrue(Files.exists(agentsDir.resolve(".aiassistant/instructions.md")))
        assertEquals("# Kotlin Agents", Files.readString(agentsDir.resolve("AGENTS.md")))
    }

    @Test
    fun `extracts files from zip archive that already contains agents prefix`() {
        val zipData = createTestZip(
            "java/agents/AGENTS.md" to "# Agents",
            "java/.fluxzero/agents/CLAUDE.md" to "# Claude"
        )

        val extractedFiles = ProjectFilesExtractor.extract(zipData, tempDir)

        assertEquals(2, extractedFiles.size)
        assertTrue(Files.exists(agentsDir.resolve("AGENTS.md")))
        assertTrue(Files.exists(agentsDir.resolve("CLAUDE.md")))
        assertFalse(Files.exists(agentsDir.resolve("agents/AGENTS.md")))
        assertFalse(Files.exists(agentsDir.resolve(".fluxzero/agents/CLAUDE.md")))
    }

    @Test
    fun `creates nested directories`() {
        val zipData = createTestZip(
            "java/.aiassistant/rules/nested/deep/file.md" to "Deep content"
        )

        ProjectFilesExtractor.extract(zipData, tempDir)

        assertTrue(Files.exists(agentsDir.resolve(".aiassistant/rules/nested/deep/file.md")))
        assertEquals("Deep content", Files.readString(agentsDir.resolve(".aiassistant/rules/nested/deep/file.md")))
    }

    @Test
    fun `overwrites existing files`() {
        // Create existing file in .fluxzero/agents
        Files.createDirectories(agentsDir)
        Files.writeString(agentsDir.resolve("AGENTS.md"), "Old content")

        val zipData = createTestZip(
            "java/AGENTS.md" to "New content"
        )

        ProjectFilesExtractor.extract(zipData, tempDir)

        assertEquals("New content", Files.readString(agentsDir.resolve("AGENTS.md")))
    }

    @Test
    fun `creates fluxzero directory when missing`() {
        val zipData = createTestZip(
            "java/AGENTS.md" to "# Agents"
        )

        assertFalse(Files.exists(fluxzeroDir))

        ProjectFilesExtractor.extract(zipData, tempDir)

        assertTrue(Files.exists(fluxzeroDir))
        assertTrue(Files.exists(agentsDir.resolve("AGENTS.md")))
    }

    @Test
    fun `cleans existing agents directory without removing fluxzero directory`() {
        // Create existing .fluxzero directory with owned agent files and unrelated customer files
        Files.createDirectories(fluxzeroDir)
        Files.writeString(fluxzeroDir.resolve("config.yaml"), "customer config")
        Files.createDirectories(agentsDir)
        Files.writeString(agentsDir.resolve("AGENTS.md"), "Old agents")
        Files.writeString(agentsDir.resolve("CLAUDE.md"), "Old claude")

        val aiAssistantDir = agentsDir.resolve(".aiassistant/rules")
        Files.createDirectories(aiAssistantDir)
        Files.writeString(aiAssistantDir.resolve("guidelines.md"), "Old guidelines")

        val removedFiles = ProjectFilesExtractor.cleanExistingFiles(tempDir)

        assertEquals(1, removedFiles.size)
        assertTrue(removedFiles.contains(".fluxzero/agents"))
        assertTrue(Files.exists(fluxzeroDir))
        assertTrue(Files.exists(fluxzeroDir.resolve("config.yaml")))
        assertFalse(Files.exists(agentsDir))
    }

    @Test
    fun `clean handles missing fluxzero directory gracefully`() {
        // No existing .fluxzero directory to clean

        val removedFiles = ProjectFilesExtractor.cleanExistingFiles(tempDir)

        assertEquals(0, removedFiles.size)
    }

    @Test
    fun `does not extract files outside fluxzero directory`() {
        // Create a malicious zip with path traversal
        val zipData = createTestZip(
            "java/../outside.txt" to "Malicious content"
        )

        val extractedFiles = ProjectFilesExtractor.extract(zipData, tempDir)

        // The malicious entry should be skipped
        assertEquals(0, extractedFiles.size)
        assertFalse(Files.exists(tempDir.resolve("outside.txt")))
    }

    @Test
    fun `handles directory entries in zip`() {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            // Add directory entry
            zos.putNextEntry(ZipEntry("java/.aiassistant/rules/"))
            zos.closeEntry()

            // Add file in directory
            zos.putNextEntry(ZipEntry("java/.aiassistant/rules/file.md"))
            zos.write("Content".toByteArray())
            zos.closeEntry()
        }

        val extractedFiles = ProjectFilesExtractor.extract(baos.toByteArray(), tempDir)

        assertEquals(1, extractedFiles.size) // Only files, not directories
        assertTrue(Files.exists(agentsDir.resolve(".aiassistant/rules/file.md")))
    }

    @Test
    fun `skips language folder entry itself`() {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            // Add java/ directory entry
            zos.putNextEntry(ZipEntry("java/"))
            zos.closeEntry()

            // Add file
            zos.putNextEntry(ZipEntry("java/AGENTS.md"))
            zos.write("Content".toByteArray())
            zos.closeEntry()
        }

        val extractedFiles = ProjectFilesExtractor.extract(baos.toByteArray(), tempDir)

        assertEquals(1, extractedFiles.size)
        assertTrue(extractedFiles.contains("AGENTS.md"))
    }

    @Test
    fun `extracts files without language prefix to agents directory`() {
        // Files without java/ or kotlin/ prefix should still work
        val zipData = createTestZip(
            "AGENTS.md" to "# Direct Agents"
        )

        val extractedFiles = ProjectFilesExtractor.extract(zipData, tempDir)

        assertEquals(1, extractedFiles.size)
        assertTrue(Files.exists(agentsDir.resolve("AGENTS.md")))
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
