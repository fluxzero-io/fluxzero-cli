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
     * Subdirectory owned by the project files sync.
     */
    const val AGENT_FILES_DIR = "agents"

    /**
     * Prefixes to strip from ZIP entries (language-specific folders).
     */
    private val LANGUAGE_PREFIXES = listOf("java/", "kotlin/")

    /**
     * Prefixes to strip from ZIP entries before writing them to .fluxzero/agents.
     */
    private val AGENT_FILE_PREFIXES = listOf(
        "$PROJECT_FILES_DIR/$AGENT_FILES_DIR/",
        "$PROJECT_FILES_DIR/",
        "$AGENT_FILES_DIR/"
    )

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
     * Files are extracted to the .fluxzero/agents subdirectory, with known wrapper prefixes stripped.
     *
     * @param zipStream The ZIP input stream
     * @param projectDir The target project directory
     * @return List of files that were extracted (relative to .fluxzero/agents/)
     */
    fun extract(zipStream: InputStream, projectDir: Path): List<String> {
        val extractedFiles = mutableListOf<String>()
        val targetDir = agentFilesDir(projectDir)
        logger.debug { "Extracting project files to $targetDir" }

        // Ensure target directory exists
        Files.createDirectories(targetDir)

        ZipInputStream(zipStream).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                // Strip known wrapper prefixes (java/, kotlin/, agents/, .fluxzero/) from entry name
                val strippedName = stripKnownPrefixes(entry.name)

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
     * Strips known wrapper prefixes from a ZIP entry name.
     */
    private fun stripKnownPrefixes(entryName: String): String {
        var strippedName = entryName

        for (prefix in LANGUAGE_PREFIXES) {
            if (strippedName.startsWith(prefix)) {
                strippedName = strippedName.removePrefix(prefix)
                break
            }
        }

        for (prefix in AGENT_FILE_PREFIXES) {
            if (strippedName.startsWith(prefix)) {
                return strippedName.removePrefix(prefix)
            }
        }

        return strippedName
    }

    /**
     * Cleans existing agent files from the .fluxzero/agents directory.
     * This ensures we don't have stale files from previous versions.
     *
     * @param projectDir The project directory containing .fluxzero
     * @return List of files that were removed
     */
    fun cleanExistingFiles(projectDir: Path): List<String> {
        val removedFiles = mutableListOf<String>()
        val targetDir = agentFilesDir(projectDir)

        // If .fluxzero/agents doesn't exist, nothing to clean
        if (!Files.exists(targetDir)) {
            return removedFiles
        }

        // Remove only the agent files directory and leave the rest of .fluxzero intact.
        deleteRecursively(targetDir)
        removedFiles.add("$PROJECT_FILES_DIR/$AGENT_FILES_DIR")
        logger.info { "Cleaned existing $PROJECT_FILES_DIR/$AGENT_FILES_DIR directory" }

        return removedFiles
    }

    fun agentFilesDir(projectDir: Path): Path {
        return projectDir.resolve(PROJECT_FILES_DIR).resolve(AGENT_FILES_DIR)
    }

    fun syncVersionFile(projectDir: Path): Path {
        return agentFilesDir(projectDir).resolve(".sync-version")
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
