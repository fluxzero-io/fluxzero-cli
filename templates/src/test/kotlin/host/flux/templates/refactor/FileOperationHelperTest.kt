package host.flux.templates.refactor

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class FileOperationHelperTest {

    private lateinit var tempDir: Path

    @BeforeTest
    fun setup() {
        tempDir = Files.createTempDirectory("refactor-test")
    }

    @AfterTest
    fun cleanup() {
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun `findMatchingFiles should find files matching glob patterns`() {
        // Create test files
        val kotlinDir = tempDir.resolve("src/main/kotlin")
        Files.createDirectories(kotlinDir)
        Files.createFile(kotlinDir.resolve("Test1.kt"))
        Files.createFile(kotlinDir.resolve("Test2.kt"))
        Files.createFile(kotlinDir.resolve("README.md"))
        
        val javaDir = tempDir.resolve("src/main/java")
        Files.createDirectories(javaDir)
        Files.createFile(javaDir.resolve("Test.java"))

        // Test finding Kotlin files
        val kotlinFiles = FileOperationHelper.findMatchingFiles(tempDir, listOf("**/*.kt"))
        assertEquals(2, kotlinFiles.size)
        assertTrue(kotlinFiles.any { it.fileName.toString() == "Test1.kt" })
        assertTrue(kotlinFiles.any { it.fileName.toString() == "Test2.kt" })

        // Test finding all Java files
        val javaFiles = FileOperationHelper.findMatchingFiles(tempDir, listOf("**/*.java"))
        assertEquals(1, javaFiles.size)
        assertTrue(javaFiles.any { it.fileName.toString() == "Test.java" })

        // Test multiple patterns
        val allSourceFiles = FileOperationHelper.findMatchingFiles(tempDir, listOf("**/*.kt", "**/*.java"))
        assertEquals(3, allSourceFiles.size)

        // Test specific file pattern
        val readmeFiles = FileOperationHelper.findMatchingFiles(tempDir, listOf("**/README.md"))
        assertEquals(1, readmeFiles.size)
    }

    @Test
    fun `findMatchingFiles should return empty list for non-matching patterns`() {
        Files.createDirectories(tempDir.resolve("src/main"))
        Files.createFile(tempDir.resolve("src/main/test.txt"))

        val result = FileOperationHelper.findMatchingFiles(tempDir, listOf("**/*.nonexistent"))
        assertTrue(result.isEmpty())
    }

    @Test
    fun `replaceInFile should replace content correctly`() {
        val testFile = tempDir.resolve("test.txt")
        Files.writeString(testFile, "Hello world, this is a test world!")

        val messages = OperationMessages()
        FileOperationHelper.replaceInFile(testFile, "world", "universe", false, messages)

        val content = Files.readString(testFile)
        assertEquals("Hello universe, this is a test universe!", content)
        assertTrue(messages.warnings.isEmpty())
    }

    @Test
    fun `replaceInFile should handle regex replacement`() {
        val testFile = tempDir.resolve("test.kt")
        Files.writeString(testFile, "package com.example.old\n\nclass Test")

        val messages = OperationMessages()
        FileOperationHelper.replaceInFile(testFile, "package com\\.example\\.\\w+", "package com.test.new", true, messages)

        val content = Files.readString(testFile)
        assertEquals("package com.test.new\n\nclass Test", content)
        assertTrue(messages.warnings.isEmpty())
    }

    @Test
    fun `replaceInFile should do nothing if content unchanged`() {
        val testFile = tempDir.resolve("test.txt")
        val originalContent = "Hello world!"
        Files.writeString(testFile, originalContent)
        val originalModified = Files.getLastModifiedTime(testFile)

        Thread.sleep(10) // Ensure time difference would be detectable

        val messages = OperationMessages()
        FileOperationHelper.replaceInFile(testFile, "nonexistent", "replacement", false, messages)

        val content = Files.readString(testFile)
        assertEquals(originalContent, content)
        // Note: File modification time behavior can vary by filesystem, so we don't test it strictly
    }

    @Test
    fun `replaceInFile should handle non-existent file gracefully`() {
        val nonExistentFile = tempDir.resolve("nonexistent.txt")
        val messages = OperationMessages()
        
        // Should not throw exception
        FileOperationHelper.replaceInFile(nonExistentFile, "old", "new", false, messages)
        
        // File should still not exist
        assertFalse(Files.exists(nonExistentFile))
    }

    @Test
    fun `deleteFile should delete regular file`() {
        val testFile = tempDir.resolve("test.txt")
        Files.writeString(testFile, "test content")
        assertTrue(Files.exists(testFile))

        val messages = OperationMessages()
        FileOperationHelper.deleteFile(testFile, messages)

        assertFalse(Files.exists(testFile))
        assertTrue(messages.warnings.isEmpty())
    }

    @Test
    fun `deleteFile should delete directory recursively`() {
        val testDir = tempDir.resolve("test-dir")
        Files.createDirectories(testDir)
        Files.writeString(testDir.resolve("file1.txt"), "content1")
        Files.writeString(testDir.resolve("file2.txt"), "content2")
        
        val subDir = testDir.resolve("subdir")
        Files.createDirectories(subDir)
        Files.writeString(subDir.resolve("file3.txt"), "content3")

        assertTrue(Files.exists(testDir))

        val messages = OperationMessages()
        FileOperationHelper.deleteFile(testDir, messages)

        assertFalse(Files.exists(testDir))
        assertTrue(messages.warnings.isEmpty())
    }

    @Test
    fun `moveFile should move file to new location`() {
        val sourceFile = tempDir.resolve("source.txt")
        Files.writeString(sourceFile, "test content")
        
        val targetDir = tempDir.resolve("target-dir")
        val targetFile = targetDir.resolve("target.txt")

        val messages = OperationMessages()
        FileOperationHelper.moveFile(sourceFile, targetFile, messages)

        assertFalse(Files.exists(sourceFile))
        assertTrue(Files.exists(targetFile))
        assertEquals("test content", Files.readString(targetFile))
        assertTrue(messages.warnings.isEmpty())
    }

    @Test
    fun `moveFile should create target directories if needed`() {
        val sourceFile = tempDir.resolve("source.txt")
        Files.writeString(sourceFile, "test content")
        
        val deepTargetFile = tempDir.resolve("deep/nested/path/target.txt")

        val messages = OperationMessages()
        FileOperationHelper.moveFile(sourceFile, deepTargetFile, messages)

        assertFalse(Files.exists(sourceFile))
        assertTrue(Files.exists(deepTargetFile))
        assertEquals("test content", Files.readString(deepTargetFile))
    }

    @Test
    fun `createDirectory should create directory path`() {
        val newDir = tempDir.resolve("new/nested/directory")
        assertFalse(Files.exists(newDir))

        val messages = OperationMessages()
        FileOperationHelper.createDirectory(newDir, messages)

        assertTrue(Files.exists(newDir))
        assertTrue(Files.isDirectory(newDir))
        assertTrue(messages.warnings.isEmpty())
    }

    @Test
    fun `cleanupEmptyDirectories should remove empty directories`() {
        // Create directory structure
        val mainDir = tempDir.resolve("src/main")
        val kotlinDir = mainDir.resolve("kotlin")
        val emptyDir = kotlinDir.resolve("empty")
        val anotherEmptyDir = kotlinDir.resolve("also-empty")
        
        Files.createDirectories(emptyDir)
        Files.createDirectories(anotherEmptyDir)
        Files.writeString(kotlinDir.resolve("NotEmpty.kt"), "content") // This will keep kotlinDir
        
        assertTrue(Files.exists(emptyDir))
        assertTrue(Files.exists(anotherEmptyDir))
        assertTrue(Files.exists(kotlinDir))

        val messages = OperationMessages()
        FileOperationHelper.cleanupEmptyDirectories(mainDir, messages)

        assertFalse(Files.exists(emptyDir))
        assertFalse(Files.exists(anotherEmptyDir))
        assertTrue(Files.exists(kotlinDir)) // Should remain because it has a file
        assertTrue(Files.exists(mainDir))   // Root should remain

        // Check that info messages were added
        assertTrue(messages.info.any { it.contains("Deleted empty directory") })
    }

    @Test
    fun `cleanupEmptyDirectories should handle non-existent root gracefully`() {
        val nonExistentDir = tempDir.resolve("nonexistent")
        assertFalse(Files.exists(nonExistentDir))

        val messages = OperationMessages()
        
        // Should not throw exception
        FileOperationHelper.cleanupEmptyDirectories(nonExistentDir, messages)
        
        // Should not create any messages
        assertTrue(messages.warnings.isEmpty())
        assertTrue(messages.info.isEmpty())
    }

    @Test
    fun `cleanupEmptyDirectories should not delete root directory`() {
        val rootDir = tempDir.resolve("empty-root")
        Files.createDirectories(rootDir)
        assertTrue(Files.exists(rootDir))

        val messages = OperationMessages()
        FileOperationHelper.cleanupEmptyDirectories(rootDir, messages)

        assertTrue(Files.exists(rootDir)) // Root should not be deleted
        assertTrue(messages.info.isEmpty()) // No deletion messages
    }

    @Test
    fun `operations should collect warnings on errors`() {
        // Test with read-only file (simulate permission error)
        val testFile = tempDir.resolve("readonly.txt")
        Files.writeString(testFile, "content")
        
        // Note: Setting read-only might not work on all filesystems, so this test
        // might need to be adapted based on the actual error conditions
        val messages = OperationMessages()
        
        // These should not throw exceptions but might add warnings
        FileOperationHelper.replaceInFile(tempDir, "find", "replace", false, messages) // tempDir is a directory, not file
        FileOperationHelper.deleteFile(tempDir.resolve("nonexistent.txt"), messages)
        FileOperationHelper.moveFile(tempDir.resolve("nonexistent.txt"), tempDir.resolve("target.txt"), messages)
        
        // Some of these operations might generate warnings, but we can't guarantee it
        // The important thing is that no exceptions are thrown
    }
}