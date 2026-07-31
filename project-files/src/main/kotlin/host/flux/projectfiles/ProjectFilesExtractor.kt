package host.flux.projectfiles

import mu.KotlinLogging
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
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

    private const val STAGING_DIRECTORY_PREFIX = ".agents-stage-"
    private const val BACKUP_DIRECTORY = ".agents-backup"

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
        val targetDir = agentFilesDir(projectDir)
        logger.debug { "Extracting project files to $targetDir" }
        return extractTo(zipStream, targetDir)
    }

    /**
     * Builds a complete replacement beside the current agent directory and promotes it only after extraction succeeds.
     * A retained backup makes an interrupted directory replacement recoverable by the next sync.
     */
    internal fun replaceAgentFiles(zipData: ByteArray, projectDir: Path, syncState: String): List<String> {
        val fluxzeroDir = projectDir.toAbsolutePath().normalize().resolve(PROJECT_FILES_DIR)
        val targetDir = fluxzeroDir.resolve(AGENT_FILES_DIR)
        val backupDir = fluxzeroDir.resolve(BACKUP_DIRECTORY)
        var stagingDir: Path? = null

        try {
            Files.createDirectories(fluxzeroDir)
            recoverInterruptedReplacement(projectDir)
            stagingDir = Files.createTempDirectory(fluxzeroDir, STAGING_DIRECTORY_PREFIX)

            val extractedFiles = extractTo(ByteArrayInputStream(zipData), stagingDir)
            if (extractedFiles.isEmpty()) {
                throw ProjectFilesSyncException("Project files archive did not contain any files")
            }
            Files.writeString(
                stagingDir.resolve(".sync-version"),
                syncState,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE
            )

            promote(stagingDir, targetDir, backupDir)
            stagingDir = null
            return extractedFiles
        } catch (e: ProjectFilesSyncException) {
            throw e
        } catch (e: Exception) {
            throw ProjectFilesSyncException("Could not replace project files in $targetDir", e)
        } finally {
            stagingDir?.let { deleteQuietly(it) }
        }
    }

    private fun extractTo(zipStream: InputStream, targetDir: Path): List<String> {
        val extractedFiles = mutableListOf<String>()

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

    internal fun recoverInterruptedReplacement(projectDir: Path) {
        try {
            val fluxzeroDir = projectDir.toAbsolutePath().normalize().resolve(PROJECT_FILES_DIR)
            val targetDir = fluxzeroDir.resolve(AGENT_FILES_DIR)
            val backupDir = fluxzeroDir.resolve(BACKUP_DIRECTORY)
            Files.createDirectories(fluxzeroDir)

            if (!Files.exists(backupDir)) {
                // Nothing to restore.
            } else if (Files.exists(targetDir)) {
                deleteRecursively(backupDir)
            } else {
                moveDirectory(backupDir, targetDir)
                logger.info { "Recovered project files from an interrupted sync" }
            }

            Files.list(fluxzeroDir).use { entries ->
                entries.filter { it.fileName.toString().startsWith(STAGING_DIRECTORY_PREFIX) }
                    .forEach { deleteQuietly(it) }
            }
        } catch (e: ProjectFilesSyncException) {
            throw e
        } catch (e: Exception) {
            throw ProjectFilesSyncException("Could not recover an interrupted project files sync for $projectDir", e)
        }
    }

    private fun promote(stagingDir: Path, targetDir: Path, backupDir: Path) {
        if (Files.exists(backupDir)) {
            deleteRecursively(backupDir)
        }
        if (Files.exists(targetDir)) {
            moveDirectory(targetDir, backupDir)
        }

        try {
            moveDirectory(stagingDir, targetDir)
        } catch (e: Exception) {
            if (!Files.exists(targetDir) && Files.exists(backupDir)) {
                moveDirectory(backupDir, targetDir)
            }
            throw e
        }

        deleteQuietly(backupDir)
    }

    private fun moveDirectory(source: Path, target: Path) {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, target)
        }
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

    private fun deleteQuietly(path: Path) {
        try {
            deleteRecursively(path)
        } catch (e: Exception) {
            logger.warn(e) { "Could not clean temporary project files directory $path" }
        }
    }
}
