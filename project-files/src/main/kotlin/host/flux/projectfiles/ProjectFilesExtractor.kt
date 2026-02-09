package host.flux.projectfiles

import mu.KotlinLogging
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipInputStream

private val logger = KotlinLogging.logger {}

/**
 * Extracts project files from a ZIP archive to a project directory.
 */
object ProjectFilesExtractor {

    /**
     * Target directory for project files within the project.
     */
    const val PROJECT_FILES_DIR = ".fluxzero"

    /**
     * Prefixes to strip from ZIP entries (language-specific folders).
     */
    private val LANGUAGE_PREFIXES = listOf("java/", "kotlin/")

    /**
     * Files/directories that are expected in project files archives.
     */
    val EXPECTED_FILES = setOf(
        "AGENTS.md",
        "CLAUDE.md",
        ".aiassistant/",
        ".junie/"
    )

    /**
     * Extracts project files from a ZIP archive to the project directory.
     *
     * @param zipData The ZIP archive data
     * @param projectDir The target project directory
     * @return List of files that were extracted
     */
    fun extract(zipData: ByteArray, projectDir: Path): List<String> {
        return extract(ByteArrayInputStream(zipData), projectDir)
    }

    /**
     * Extracts project files from a ZIP input stream to the project directory.
     * Files are extracted to the .fluxzero/ subdirectory, with language prefixes stripped.
     *
     * @param zipStream The ZIP input stream
     * @param projectDir The target project directory
     * @return List of files that were extracted (relative to .fluxzero/)
     */
    fun extract(zipStream: InputStream, projectDir: Path): List<String> {
        val extractedFiles = mutableListOf<String>()
        val targetDir = projectDir.resolve(PROJECT_FILES_DIR)
        logger.debug { "Extracting project files to $targetDir" }

        // Ensure target directory exists
        Files.createDirectories(targetDir)

        ZipInputStream(zipStream).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                // Strip language prefix (java/ or kotlin/) from entry name
                val strippedName = stripLanguagePrefix(entry.name)

                // Skip if entry is just the language folder itself
                if (strippedName.isEmpty()) {
                    entry = zis.nextEntry
                    continue
                }

                val entryPath = targetDir.resolve(strippedName).normalize()

                // Security check: ensure we don't extract outside the target directory
                if (!entryPath.startsWith(targetDir)) {
                    logger.warn { "Skipping potentially malicious entry: ${entry.name}" }
                    entry = zis.nextEntry
                    continue
                }

                if (entry.isDirectory) {
                    Files.createDirectories(entryPath)
                    logger.debug { "Created directory: $strippedName" }
                } else {
                    // Ensure parent directories exist
                    Files.createDirectories(entryPath.parent)

                    // Extract file
                    Files.copy(zis, entryPath, StandardCopyOption.REPLACE_EXISTING)
                    extractedFiles.add(strippedName)
                    logger.debug { "Extracted: $strippedName" }
                }

                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        logger.info { "Extracted ${extractedFiles.size} files to $targetDir" }
        return extractedFiles
    }

    /**
     * Strips language prefix (java/ or kotlin/) from a ZIP entry name.
     */
    private fun stripLanguagePrefix(entryName: String): String {
        for (prefix in LANGUAGE_PREFIXES) {
            if (entryName.startsWith(prefix)) {
                return entryName.removePrefix(prefix)
            }
        }
        return entryName
    }

    /**
     * Cleans existing project files from the .fluxzero directory.
     * This ensures we don't have stale files from previous versions.
     *
     * @param projectDir The project directory containing .fluxzero
     * @return List of files that were removed
     */
    fun cleanExistingFiles(projectDir: Path): List<String> {
        val removedFiles = mutableListOf<String>()
        val targetDir = projectDir.resolve(PROJECT_FILES_DIR)

        // If .fluxzero doesn't exist, nothing to clean
        if (!Files.exists(targetDir)) {
            return removedFiles
        }

        // Remove the entire .fluxzero directory and recreate it fresh
        deleteRecursively(targetDir)
        removedFiles.add(PROJECT_FILES_DIR)
        logger.info { "Cleaned existing $PROJECT_FILES_DIR directory" }

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
