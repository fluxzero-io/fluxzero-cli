package host.flux.templates.refactor

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/**
 * Integration test that verifies refactoring works on the actual test-template
 */
class TestTemplateIntegrationTest {

    private lateinit var tempDir: Path
    private val templateRefactor = TemplateRefactor()

    @BeforeTest
    fun setup() {
        tempDir = Files.createTempDirectory("test-template-integration")
    }

    @AfterTest
    fun cleanup() {
        tempDir.toFile().deleteRecursively()
    }

    private fun copyTestTemplate(): Path {
        val testTemplateResource = javaClass.classLoader.getResource("templates/test-template")
            ?: fail("Test template not found in resources")
        
        val testTemplateDir = Path.of(testTemplateResource.toURI())
        val targetDir = tempDir.resolve("template-copy")
        
        testTemplateDir.toFile().copyRecursively(targetDir.toFile())
        return targetDir
    }

    @Test
    fun `should successfully refactor the test template`() {
        val templateDir = copyTestTemplate()
        val variables = TemplateVariables(
            packageName = "com.integration.test",
            projectName = "integration-app"
        )

        // Verify initial state
        assertTrue(Files.exists(templateDir.resolve("refactor.yaml")))
        assertTrue(Files.exists(templateDir.resolve("src/main/kotlin/com/example/template/Application.kt")))

        // Execute refactoring
        val result = templateRefactor.refactorTemplate(templateDir, variables)

        // Verify success
        assertTrue(result.success, "Refactoring should succeed: ${result.message}")
        assertTrue(result.operationsExecuted >= 5, "Should execute multiple operations")

        // Verify key transformations worked
        assertTrue(Files.exists(templateDir.resolve("src/main/kotlin/com/integration/test/Application.kt")))
        assertFalse(Files.exists(templateDir.resolve("src/main/kotlin/com/example")))
        assertFalse(Files.exists(templateDir.resolve("refactor.yaml")))
        
        // Verify package was replaced in code
        val appContent = Files.readString(templateDir.resolve("src/main/kotlin/com/integration/test/Application.kt"))
        assertTrue(appContent.contains("package com.integration.test"))
    }

    @Test
    fun `should handle individual operations on test template content`() {
        val templateDir = copyTestTemplate()
        val variables = TemplateVariables("com.individual.ops", "ops-test")

        // Test Replace Operation
        val replaceOp = ReplaceOperation(
            files = listOf("**/*.kt"),
            find = "com\\.example\\.template",
            replace = "\${package}",
            regex = true
        )
        
        val messages = replaceOp.execute(templateDir, variables)
        assertTrue(messages.warnings.isEmpty())

        // Verify replacement worked
        val serviceContent = Files.readString(templateDir.resolve("src/main/kotlin/com/example/template/service/TestService.kt"))
        assertTrue(serviceContent.contains("package com.individual.ops.service"))
        assertTrue(serviceContent.contains("Hello from com.individual.ops"))
    }

    @Test
    fun `should handle directory rename operations on test template`() {
        val templateDir = copyTestTemplate()
        val variables = TemplateVariables("com.rename.test", "rename-app")

        val renameOp = RenameOperation(
            from = "src/main/kotlin/com/example/template",
            to = "src/main/kotlin/\${packagePath}"
        )
        
        val messages = renameOp.execute(templateDir, variables)
        assertTrue(messages.warnings.isEmpty())

        // Verify rename worked
        val newDir = templateDir.resolve("src/main/kotlin/com/rename/test")
        assertTrue(Files.exists(newDir))
        assertTrue(Files.exists(newDir.resolve("Application.kt")))
        assertTrue(Files.exists(newDir.resolve("service/TestService.kt")))
        
        // Old directory should be gone
        assertFalse(Files.exists(templateDir.resolve("src/main/kotlin/com/example/template")))
    }

    @Test
    fun `should handle delete operations on test template`() {
        val templateDir = copyTestTemplate()
        val variables = TemplateVariables("com.delete.test", "delete-app")

        // Verify .tmp file exists initially
        assertTrue(Files.exists(templateDir.resolve("delete-me.tmp")))

        val deleteOp = DeleteOperation(files = listOf("**/*.tmp"))
        val messages = deleteOp.execute(templateDir, variables)
        assertTrue(messages.warnings.isEmpty())

        // Verify .tmp file was deleted
        assertFalse(Files.exists(templateDir.resolve("delete-me.tmp")))
        
        // Other files should remain
        assertTrue(Files.exists(templateDir.resolve("build.gradle.kts")))
    }

    @Test
    fun `should demonstrate complete refactoring workflow`() {
        val templateDir = copyTestTemplate()
        val variables = TemplateVariables("org.workflow.demo", "workflow-demo")

        // This test demonstrates the complete workflow from template to customized project
        val result = templateRefactor.refactorTemplate(templateDir, variables)
        
        assertTrue(result.success)
        
        // Verify the end result looks like a properly customized project
        val expectedFiles = listOf(
            "src/main/kotlin/org/workflow/demo/Application.kt",
            "src/main/kotlin/org/workflow/demo/service/TestService.kt", 
            "src/test/kotlin/org/workflow/demo/ApplicationTest.kt",
            "build.gradle.kts",
            "pom.xml",
            "logs" // created directory
        )
        
        expectedFiles.forEach { file ->
            val path = templateDir.resolve(file)
            assertTrue(Files.exists(path), "Expected file/directory should exist: $file")
        }
        
        // Verify build files contain correct project info
        val gradleContent = Files.readString(templateDir.resolve("build.gradle.kts"))
        assertTrue(gradleContent.contains("org.workflow.demo"))
        assertTrue(gradleContent.contains("workflow-demo"))
        
        val pomContent = Files.readString(templateDir.resolve("pom.xml"))
        assertTrue(pomContent.contains("<groupId>org.workflow.demo</groupId>"))
        assertTrue(pomContent.contains("<artifactId>workflow-demo</artifactId>"))
    }
}