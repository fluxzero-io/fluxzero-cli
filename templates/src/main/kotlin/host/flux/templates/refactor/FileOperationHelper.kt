package host.flux.templates.refactor

import java.io.IOException
import java.nio.charset.MalformedInputException
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import kotlin.streams.toList


object FileOperationHelper {
    
    fun findMatchingFiles(templateRoot: Path, patterns: List<String>): List<Path> {
        val allFiles = mutableListOf<Path>()
        
        patterns.forEach { pattern ->
            val matcher = FileSystems.getDefault().getPathMatcher("glob:$pattern")
            
            Files.walkFileTree(templateRoot, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val relativePath = templateRoot.relativize(file)
                    if (matcher.matches(relativePath)) {
                        allFiles.add(file)
                    }
                    return FileVisitResult.CONTINUE
                }
                
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val relativePath = templateRoot.relativize(dir)
                    if (matcher.matches(relativePath) && dir != templateRoot) {
                        allFiles.add(dir)
                    }
                    return FileVisitResult.CONTINUE
                }
            })
        }
        
        return allFiles.distinct()
    }
    
    fun replaceInFile(file: Path, find: String, replace: String, regex: Boolean, messages: OperationMessages = OperationMessages()) {
        if (!Files.exists(file) || !Files.isRegularFile(file)) return

        try {
            val content = Files.readString(file)
            val newContent = if (regex) {
                content.replace(Regex(find), replace)
            } else {
                content.replace(find, replace)
            }

            if (content != newContent) {
                Files.writeString(file, newContent)
            }
        } catch (_: MalformedInputException) {
            // Binary file — silently skip, no text patterns to replace
        } catch (e: Exception) {
            messages.warnings.add("Failed to replace in file ${file}: ${e.message}")
        }
    }
    
    fun deleteFile(file: Path, messages: OperationMessages = OperationMessages()) {
        try {
            if (Files.exists(file)) {
                if (Files.isDirectory(file)) {
                    deleteDirectory(file)
                } else {
                    Files.delete(file)
                }
            }
        } catch (e: Exception) {
            messages.warnings.add("Failed to delete ${file}: ${e.message}")
        }
    }
    
    private fun deleteDirectory(directory: Path) {
        Files.walkFileTree(directory, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }
            
            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }
    
    fun moveFile(sourcePath: Path, targetPath: Path, messages: OperationMessages = OperationMessages()) {
        try {
            if (Files.exists(sourcePath)) {
                Files.createDirectories(targetPath.parent)
                Files.move(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: Exception) {
            messages.warnings.add("Failed to move ${sourcePath} to ${targetPath}: ${e.message}")
        }
    }
    
    fun createDirectory(directory: Path, messages: OperationMessages = OperationMessages()) {
        try {
            Files.createDirectories(directory)
        } catch (e: Exception) {
            messages.warnings.add("Failed to create directory ${directory}: ${e.message}")
        }
    }
    
    fun cleanupEmptyDirectories(rootPath: Path, messages: OperationMessages = OperationMessages()) {
        if (!Files.exists(rootPath) || !Files.isDirectory(rootPath)) {
            return
        }
        
        try {
            // Walk the directory tree in reverse order (bottom-up) to delete empty directories
            Files.walkFileTree(rootPath, object : SimpleFileVisitor<Path>() {
                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    if (exc == null && dir != rootPath) {
                        try {
                            // Try to delete the directory if it's empty
                            if (isDirEmpty(dir)) {
                                Files.delete(dir)
                                messages.info.add("Deleted empty directory: ${rootPath.relativize(dir)}")
                            }
                        } catch (e: Exception) {
                            // Directory not empty or other error - ignore and continue
                        }
                    }
                    return FileVisitResult.CONTINUE
                }
            })
        } catch (e: Exception) {
            messages.warnings.add("Failed to cleanup empty directories in ${rootPath}: ${e.message}")
        }
    }
    
    fun chmodFiles(files: List<Path>, mode: String, messages: OperationMessages = OperationMessages()) {
        files.forEach { file ->
            try {
                if (Files.exists(file)) {
                    val permissions = parseFileMode(mode)
                    Files.setPosixFilePermissions(file, permissions)
                    messages.info.add("Set permissions $mode on file: $file")
                }
            } catch (e: UnsupportedOperationException) {
                messages.warnings.add("Cannot set POSIX permissions on ${file}: filesystem does not support POSIX attributes")
            } catch (e: Exception) {
                messages.warnings.add("Failed to set permissions on ${file}: ${e.message}")
            }
        }
    }

    private fun parseFileMode(mode: String): Set<PosixFilePermission> {
        return when {
            mode.matches(Regex("^[0-7]{3,4}$")) -> {
                // Octal notation (e.g., "755", "644")
                val octalValue = mode.takeLast(3).toInt(8)
                val permissions = mutableSetOf<PosixFilePermission>()

                // Owner permissions
                if ((octalValue and 0x100) != 0) permissions.add(PosixFilePermission.OWNER_READ)   // 0o400
                if ((octalValue and 0x080) != 0) permissions.add(PosixFilePermission.OWNER_WRITE)  // 0o200
                if ((octalValue and 0x040) != 0) permissions.add(PosixFilePermission.OWNER_EXECUTE) // 0o100

                // Group permissions
                if ((octalValue and 0x020) != 0) permissions.add(PosixFilePermission.GROUP_READ)    // 0o040
                if ((octalValue and 0x010) != 0) permissions.add(PosixFilePermission.GROUP_WRITE)   // 0o020
                if ((octalValue and 0x008) != 0) permissions.add(PosixFilePermission.GROUP_EXECUTE) // 0o010

                // Others permissions
                if ((octalValue and 0x004) != 0) permissions.add(PosixFilePermission.OTHERS_READ)    // 0o004
                if ((octalValue and 0x002) != 0) permissions.add(PosixFilePermission.OTHERS_WRITE)   // 0o002
                if ((octalValue and 0x001) != 0) permissions.add(PosixFilePermission.OTHERS_EXECUTE) // 0o001

                permissions
            }
            mode.matches(Regex("^[rwx-]{9}$")) -> {
                // Symbolic notation (e.g., "rwxr-xr-x")
                PosixFilePermissions.fromString(mode)
            }
            else -> {
                throw IllegalArgumentException("Invalid file mode format: $mode. Use octal (e.g., '755') or symbolic (e.g., 'rwxr-xr-x') notation.")
            }
        }
    }

    private fun isDirEmpty(directory: Path): Boolean {
        return try {
            Files.newDirectoryStream(directory).use { stream ->
                !stream.iterator().hasNext()
            }
        } catch (e: Exception) {
            false
        }
    }
}