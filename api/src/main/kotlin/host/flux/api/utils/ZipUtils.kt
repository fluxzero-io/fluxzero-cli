package host.flux.api.utils

import java.io.IOException
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread

object ZipUtils {

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
                ZipOutputStream(pipedOutputStream).use { zipOut ->
                    Files.walkFileTree(sourceDir, object : SimpleFileVisitor<Path>() {
                        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                            val relativePath = sourceDir.relativize(file)
                            val zipEntry = ZipEntry("$projectName/$relativePath")

                            zipOut.putNextEntry(zipEntry)
                            Files.copy(file, zipOut)
                            zipOut.closeEntry()

                            return FileVisitResult.CONTINUE
                        }

                        override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                            if (dir != sourceDir) {
                                val relativePath = sourceDir.relativize(dir)
                                val zipEntry = ZipEntry("$projectName/$relativePath/")
                                zipOut.putNextEntry(zipEntry)
                                zipOut.closeEntry()
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

}