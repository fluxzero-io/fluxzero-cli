package host.flux.maven.core

import mu.KotlinLogging
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipInputStream

private val logger = KotlinLogging.logger {}

/**
 * Extracts agent files from a ZIP archive to a project directory.
 */
object AgentFilesExtractor {

    /**
     * Extracts agent files from a ZIP archive to the project directory.
     */
    fun extract(zipData: ByteArray, projectDir: Path): List<String> {
        return extract(ByteArrayInputStream(zipData), projectDir)
    }

    /**
     * Extracts agent files from a ZIP input stream to the project directory.
     */
    fun extract(zipStream: InputStream, projectDir: Path): List<String> {
        val extractedFiles = mutableListOf<String>()
        logger.debug { "Extracting agent files to $projectDir" }

        ZipInputStream(zipStream).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val entryPath = projectDir.resolve(entry.name).normalize()

                // Security check
                if (!entryPath.startsWith(projectDir)) {
                    logger.warn { "Skipping potentially malicious entry: ${entry.name}" }
                    entry = zis.nextEntry
                    continue
                }

                if (entry.isDirectory) {
                    Files.createDirectories(entryPath)
                    logger.debug { "Created directory: ${entry.name}" }
                } else {
                    Files.createDirectories(entryPath.parent)
                    Files.copy(zis, entryPath, StandardCopyOption.REPLACE_EXISTING)
                    extractedFiles.add(entry.name)
                    logger.debug { "Extracted: ${entry.name}" }
                }

                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        logger.info { "Extracted ${extractedFiles.size} files to $projectDir" }
        return extractedFiles
    }

    /**
     * Cleans existing agent files from the project directory.
     */
    fun cleanExistingFiles(projectDir: Path): List<String> {
        val removedFiles = mutableListOf<String>()

        val filesToRemove = listOf("AGENTS.md", "CLAUDE.md")
        val dirsToRemove = listOf(".aiassistant", ".junie")

        for (file in filesToRemove) {
            val filePath = projectDir.resolve(file)
            if (Files.exists(filePath)) {
                Files.delete(filePath)
                removedFiles.add(file)
                logger.debug { "Removed file: $file" }
            }
        }

        for (dir in dirsToRemove) {
            val dirPath = projectDir.resolve(dir)
            if (Files.exists(dirPath) && Files.isDirectory(dirPath)) {
                deleteRecursively(dirPath)
                removedFiles.add(dir)
                logger.debug { "Removed directory: $dir" }
            }
        }

        if (removedFiles.isNotEmpty()) {
            logger.info { "Cleaned ${removedFiles.size} existing agent files/directories" }
        }

        return removedFiles
    }

    private fun deleteRecursively(path: Path) {
        if (Files.isDirectory(path)) {
            Files.list(path).use { stream ->
                stream.forEach { deleteRecursively(it) }
            }
        }
        Files.deleteIfExists(path)
    }
}
