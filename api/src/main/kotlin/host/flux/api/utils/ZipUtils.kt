package host.flux.api.utils

import org.apache.commons.compress.archivers.zip.UnixStat
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission
import kotlin.concurrent.thread

object ZipUtils {

    /**
     * Creates a ZIP file containing all files and directories from the specified directory.
     * Returns the Path to the created ZIP file.
     */
    fun zipDirectoryToFile(sourceDir: Path, projectName: String): Path {
        val tempZipFile = Files.createTempFile("project-", ".zip")
        
        ZipArchiveOutputStream(Files.newOutputStream(tempZipFile)).use { zipOut ->
            Files.walkFileTree(sourceDir, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val relativePath = sourceDir.relativize(file)
                    val zipEntry = ZipArchiveEntry(zipEntryName(projectName, relativePath))
                    zipEntry.setUnixMode(unixMode(file, isDirectory = false))

                    zipOut.putArchiveEntry(zipEntry)
                    Files.newInputStream(file).use { input -> input.copyTo(zipOut) }
                    zipOut.closeArchiveEntry()

                    return FileVisitResult.CONTINUE
                }

                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (dir != sourceDir) {
                        val relativePath = sourceDir.relativize(dir)
                        val zipEntry = ZipArchiveEntry(zipEntryName(projectName, relativePath, isDirectory = true))
                        zipEntry.setUnixMode(unixMode(dir, isDirectory = true))
                        zipOut.putArchiveEntry(zipEntry)
                        zipOut.closeArchiveEntry()
                    }
                    return FileVisitResult.CONTINUE
                }
            })
        }
        
        return tempZipFile
    }

    /**
     * Creates a streaming ZIP file containing all files and directories from the specified directory.
     * Returns an InputStream that can be read to get the ZIP file content.
     */
    fun zipDirectoryStreaming(sourceDir: Path, projectName: String): InputStream {
        val pipedOutputStream = PipedOutputStream()
        val pipedInputStream = PipedInputStream(pipedOutputStream)

        // Create ZIP content in a background thread
        thread {
            try {
                ZipArchiveOutputStream(pipedOutputStream).use { zipOut ->
                    Files.walkFileTree(sourceDir, object : SimpleFileVisitor<Path>() {
                        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                            val relativePath = sourceDir.relativize(file)
                            val zipEntry = ZipArchiveEntry(zipEntryName(projectName, relativePath))
                            zipEntry.setUnixMode(unixMode(file, isDirectory = false))

                            zipOut.putArchiveEntry(zipEntry)
                            Files.newInputStream(file).use { input -> input.copyTo(zipOut) }
                            zipOut.closeArchiveEntry()

                            return FileVisitResult.CONTINUE
                        }

                        override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                            if (dir != sourceDir) {
                                val relativePath = sourceDir.relativize(dir)
                                val zipEntry = ZipArchiveEntry(zipEntryName(projectName, relativePath, isDirectory = true))
                                zipEntry.setUnixMode(unixMode(dir, isDirectory = true))
                                zipOut.putArchiveEntry(zipEntry)
                                zipOut.closeArchiveEntry()
                            }
                            return FileVisitResult.CONTINUE
                        }
                    })
                }
            } catch (e: Exception) {
                // Close the output stream on error to signal end of stream
                try {
                    pipedOutputStream.close()
                } catch (_: IOException) {
                    // Ignore close errors
                }
                throw e
            }
        }

        return pipedInputStream
    }

    private fun zipEntryName(projectName: String, relativePath: Path, isDirectory: Boolean = false): String {
        val path = relativePath.joinToString(separator = "/") { it.toString() }
        return buildString {
            append(projectName)
            if (path.isNotEmpty()) {
                append("/")
                append(path)
            }
            if (isDirectory) {
                append("/")
            }
        }
    }

    private fun unixMode(path: Path, isDirectory: Boolean): Int {
        val posixPermissions = try {
            Files.getPosixFilePermissions(path)
        } catch (_: UnsupportedOperationException) {
            null
        }

        if (posixPermissions != null) {
            return posixPermissions.toUnixMode()
        }

        return when {
            isDirectory -> UnixStat.DEFAULT_DIR_PERM
            Files.isExecutable(path) -> UnixStat.DEFAULT_DIR_PERM
            else -> UnixStat.DEFAULT_FILE_PERM
        }
    }

    private fun Set<PosixFilePermission>.toUnixMode(): Int {
        var mode = 0
        if (contains(PosixFilePermission.OWNER_READ)) mode = mode or 0b100_000_000
        if (contains(PosixFilePermission.OWNER_WRITE)) mode = mode or 0b010_000_000
        if (contains(PosixFilePermission.OWNER_EXECUTE)) mode = mode or 0b001_000_000
        if (contains(PosixFilePermission.GROUP_READ)) mode = mode or 0b000_100_000
        if (contains(PosixFilePermission.GROUP_WRITE)) mode = mode or 0b000_010_000
        if (contains(PosixFilePermission.GROUP_EXECUTE)) mode = mode or 0b000_001_000
        if (contains(PosixFilePermission.OTHERS_READ)) mode = mode or 0b000_000_100
        if (contains(PosixFilePermission.OTHERS_WRITE)) mode = mode or 0b000_000_010
        if (contains(PosixFilePermission.OTHERS_EXECUTE)) mode = mode or 0b000_000_001
        return mode
    }

}
