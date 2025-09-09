package host.flux.templates.refactor

import java.io.IOException
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
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