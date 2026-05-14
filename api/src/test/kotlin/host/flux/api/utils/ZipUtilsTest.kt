package host.flux.api.utils

import org.apache.commons.compress.archivers.zip.ZipFile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class ZipUtilsTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `zipDirectoryToFile stores executable Unix mode`() {
        val sourceDir = createProjectDir()
        val zipFile = ZipUtils.zipDirectoryToFile(sourceDir, "project")

        assertExecutableEntry(zipFile)
    }

    @Test
    fun `zipDirectoryStreaming stores executable Unix mode`() {
        val sourceDir = createProjectDir()
        val zipFile = tempDir.resolve("streamed.zip")

        ZipUtils.zipDirectoryStreaming(sourceDir, "project").use { input ->
            Files.copy(input, zipFile)
        }

        assertExecutableEntry(zipFile)
    }

    private fun createProjectDir(): Path {
        val sourceDir = tempDir.resolve("project")
        Files.createDirectories(sourceDir)

        val wrapper = sourceDir.resolve("mvnw")
        Files.writeString(wrapper, "#!/bin/sh\n")
        wrapper.toFile().setExecutable(true, false)

        Files.writeString(sourceDir.resolve("README.md"), "# Project\n")
        return sourceDir
    }

    private fun assertExecutableEntry(zipFile: Path) {
        ZipFile.builder().setPath(zipFile).get().use { zip ->
            val wrapperEntry = zip.getEntry("project/mvnw")
            assertNotNull(wrapperEntry)
            assertEquals(0b001_001_001, wrapperEntry.unixMode and 0b001_001_001)

            val readmeEntry = zip.getEntry("project/README.md")
            assertNotNull(readmeEntry)
            assertEquals(0, readmeEntry.unixMode and 0b001_001_001)
        }
    }
}
