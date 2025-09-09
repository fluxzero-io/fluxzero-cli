package host.flux.templates.refactor

import io.mockk.*
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RefactorOperationTest {

    private val testVariables = TemplateVariables(
        packageName = "com.test.demo",
        projectName = "test-project",
        groupId = "org.test"
    )

    @Test
    fun `ReplaceOperation should expand variables and call FileOperationHelper`() {
        mockkObject(FileOperationHelper)
        val templateRoot = Paths.get("/tmp/template")
        val testFiles = listOf(Paths.get("/tmp/template/test.kt"))
        
        every { FileOperationHelper.findMatchingFiles(any(), any()) } returns testFiles
        every { FileOperationHelper.replaceInFile(any(), any(), any(), any(), any()) } just Runs

        val operation = ReplaceOperation(
            files = listOf("**/*.kt"),
            find = "package com\\.example\\.flux",
            replace = "package \${package}",
            regex = true
        )

        val result = operation.execute(templateRoot, testVariables)

        verify { FileOperationHelper.findMatchingFiles(templateRoot, listOf("**/*.kt")) }
        verify { 
            FileOperationHelper.replaceInFile(
                testFiles[0],
                "package com\\.example\\.flux",
                "package com.test.demo",
                true,
                any()
            )
        }
        
        assertTrue(result.warnings.isEmpty())
        unmockkObject(FileOperationHelper)
    }

    @Test
    fun `ReplaceOperation should handle non-regex replacement`() {
        mockkObject(FileOperationHelper)
        val templateRoot = Paths.get("/tmp/template")
        val testFiles = listOf(Paths.get("/tmp/template/pom.xml"))
        
        every { FileOperationHelper.findMatchingFiles(any(), any()) } returns testFiles
        every { FileOperationHelper.replaceInFile(any(), any(), any(), any(), any()) } just Runs

        val operation = ReplaceOperation(
            files = listOf("pom.xml"),
            find = "com.example.app",
            replace = "\${package}",
            regex = false
        )

        operation.execute(templateRoot, testVariables)

        verify { 
            FileOperationHelper.replaceInFile(
                testFiles[0],
                "com.example.app", // find should not be expanded for non-regex
                "com.test.demo",   // replace should be expanded
                false,
                any()
            )
        }
        
        unmockkObject(FileOperationHelper)
    }

    @Test
    fun `DeleteOperation should call FileOperationHelper for each matched file`() {
        mockkObject(FileOperationHelper)
        val templateRoot = Paths.get("/tmp/template")
        val testFiles = listOf(
            Paths.get("/tmp/template/file1.tmp"),
            Paths.get("/tmp/template/file2.tmp")
        )
        
        every { FileOperationHelper.findMatchingFiles(any(), any()) } returns testFiles
        every { FileOperationHelper.deleteFile(any(), any()) } just Runs

        val operation = DeleteOperation(files = listOf("**/*.tmp"))
        val result = operation.execute(templateRoot, testVariables)

        verify { FileOperationHelper.deleteFile(testFiles[0], any()) }
        verify { FileOperationHelper.deleteFile(testFiles[1], any()) }
        assertTrue(result.warnings.isEmpty())
        
        unmockkObject(FileOperationHelper)
    }

    @Test
    fun `RenameOperation should expand variables in paths`() {
        mockkObject(FileOperationHelper)
        val templateRoot = Paths.get("/tmp/template")
        
        every { FileOperationHelper.moveFile(any(), any(), any()) } just Runs

        val operation = RenameOperation(
            from = "src/main/kotlin/com/example/flux",
            to = "src/main/kotlin/\${packagePath}"
        )

        operation.execute(templateRoot, testVariables)

        verify { 
            FileOperationHelper.moveFile(
                templateRoot.resolve("src/main/kotlin/com/example/flux"),
                templateRoot.resolve("src/main/kotlin/com/test/demo"),
                any()
            )
        }
        
        unmockkObject(FileOperationHelper)
    }

    @Test
    fun `CreateDirectoryOperation should expand variables in directory path`() {
        mockkObject(FileOperationHelper)
        val templateRoot = Paths.get("/tmp/template")
        
        every { FileOperationHelper.createDirectory(any(), any()) } just Runs

        val operation = CreateDirectoryOperation(
            directory = "src/main/kotlin/\${packagePath}"
        )

        operation.execute(templateRoot, testVariables)

        verify { 
            FileOperationHelper.createDirectory(
                templateRoot.resolve("src/main/kotlin/com/test/demo"),
                any()
            )
        }
        
        unmockkObject(FileOperationHelper)
    }

    @Test
    fun `CleanupEmptyDirectoriesOperation should process all specified paths`() {
        mockkObject(FileOperationHelper)
        val templateRoot = Paths.get("/tmp/template")
        
        every { FileOperationHelper.cleanupEmptyDirectories(any(), any()) } just Runs

        val operation = CleanupEmptyDirectoriesOperation(
            paths = listOf("src/main", "src/test", "custom/path")
        )

        operation.execute(templateRoot, testVariables)

        verify { FileOperationHelper.cleanupEmptyDirectories(templateRoot.resolve("src/main"), any()) }
        verify { FileOperationHelper.cleanupEmptyDirectories(templateRoot.resolve("src/test"), any()) }
        verify { FileOperationHelper.cleanupEmptyDirectories(templateRoot.resolve("custom/path"), any()) }
        
        unmockkObject(FileOperationHelper)
    }

    @Test
    fun `variable expansion should handle both placeholder formats`() {
        mockkObject(FileOperationHelper)
        val templateRoot = Paths.get("/tmp/template")
        val testFiles = listOf(Paths.get("/tmp/template/test.kt"))
        
        every { FileOperationHelper.findMatchingFiles(any(), any()) } returns testFiles
        every { FileOperationHelper.replaceInFile(any(), any(), any(), any(), any()) } just Runs

        // Test both ${} and {{}} formats
        val operation1 = ReplaceOperation(
            files = listOf("*.kt"),
            find = "old",
            replace = "\${package}",
            regex = false
        )

        val operation2 = ReplaceOperation(
            files = listOf("*.kt"),
            find = "old", 
            replace = "{{packagePath}}",
            regex = false
        )

        operation1.execute(templateRoot, testVariables)
        operation2.execute(templateRoot, testVariables)

        verify { 
            FileOperationHelper.replaceInFile(
                any(), any(), "com.test.demo", false, any()
            )
        }
        verify { 
            FileOperationHelper.replaceInFile(
                any(), any(), "com/test/demo", false, any()
            )
        }
        
        unmockkObject(FileOperationHelper)
    }

    @Test
    fun `should expand all supported variable types`() {
        mockkObject(FileOperationHelper)
        val templateRoot = Paths.get("/tmp/template")
        val testFiles = listOf(Paths.get("/tmp/template/test.txt"))
        
        every { FileOperationHelper.findMatchingFiles(any(), any()) } returns testFiles
        every { FileOperationHelper.replaceInFile(any(), any(), any(), any(), any()) } just Runs

        val operation = ReplaceOperation(
            files = listOf("*.txt"),
            find = "placeholder",
            replace = "\${package}-\${packagePath}-\${projectName}-\${groupId}-\${artifactId}",
            regex = false
        )

        operation.execute(templateRoot, testVariables)

        verify { 
            FileOperationHelper.replaceInFile(
                any(), 
                any(), 
                "com.test.demo-com/test/demo-test-project-org.test-test-project", 
                false, 
                any()
            )
        }
        
        unmockkObject(FileOperationHelper)
    }
}