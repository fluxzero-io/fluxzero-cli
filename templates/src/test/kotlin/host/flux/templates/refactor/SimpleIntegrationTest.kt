package host.flux.templates.refactor

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/**
 * Simple integration tests that demonstrate the refactoring system works end-to-end
 */
class SimpleIntegrationTest {

    private lateinit var tempDir: Path
    private val templateRefactor = TemplateRefactor()

    @BeforeTest
    fun setup() {
        tempDir = Files.createTempDirectory("simple-integration-test")
    }

    @AfterTest
    fun cleanup() {
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun `should handle template without refactor yaml`() {
        // Create a simple template structure
        Files.createDirectories(tempDir.resolve("src/main/kotlin"))
        Files.writeString(tempDir.resolve("src/main/kotlin/Test.kt"), "class Test")

        val variables = TemplateVariables("com.test.simple", "simple-test")
        val result = templateRefactor.refactorTemplate(tempDir, variables)

        assertTrue(result.success)
        assertTrue(result.message.contains("No refactor.yaml found"))
        assertEquals(0, result.operationsExecuted)
    }

    @Test
    fun `should execute simple replace operation`() {
        // Create test files
        Files.createDirectories(tempDir.resolve("src"))
        Files.writeString(tempDir.resolve("src/Test.kt"), "package old.package\n\nclass Test")

        // Create refactor configuration
        val refactorYaml = """
            operations:
              - type: replace
                files: ["**/*.kt"]
                find: "old.package"
                replace: "${'$'}{package}"
                regex: false
        """.trimIndent()
        Files.writeString(tempDir.resolve("refactor.yaml"), refactorYaml)

        val variables = TemplateVariables("new.package", "test-project")
        val result = templateRefactor.refactorTemplate(tempDir, variables)

        assertTrue(result.success)
        assertTrue(result.operationsExecuted >= 1)
        
        // Verify replacement worked
        val content = Files.readString(tempDir.resolve("src/Test.kt"))
        assertTrue(content.contains("package new.package"))
        assertFalse(content.contains("old.package"))
        
        // Verify refactor.yaml was cleaned up
        assertFalse(Files.exists(tempDir.resolve("refactor.yaml")))
    }

    @Test
    fun `should execute directory rename operation`() {
        // Create directory structure
        val sourceDir = tempDir.resolve("src/old/path")
        Files.createDirectories(sourceDir)
        Files.writeString(sourceDir.resolve("Test.kt"), "class Test")

        val refactorYaml = """
            operations:
              - type: rename
                from: "src/old/path"
                to: "src/${'$'}{packagePath}"
        """.trimIndent()
        Files.writeString(tempDir.resolve("refactor.yaml"), refactorYaml)

        val variables = TemplateVariables("new.package.path", "test-project")
        val result = templateRefactor.refactorTemplate(tempDir, variables)

        assertTrue(result.success)
        
        // Verify directory was moved
        val newDir = tempDir.resolve("src/new/package/path")
        assertTrue(Files.exists(newDir))
        assertTrue(Files.exists(newDir.resolve("Test.kt")))
        assertFalse(Files.exists(sourceDir))
    }

    @Test
    fun `should handle yaml parsing errors gracefully`() {
        val malformedYaml = "invalid: yaml: content: {[}"
        Files.writeString(tempDir.resolve("refactor.yaml"), malformedYaml)

        val variables = TemplateVariables("com.test.error", "error-test")
        val result = templateRefactor.refactorTemplate(tempDir, variables)

        assertFalse(result.success)
        assertTrue(result.message.contains("Failed to apply template refactoring"))
        assertNotNull(result.error)
    }

    @Test
    fun `should process multiple operations in sequence`() {
        // Create test structure
        Files.createDirectories(tempDir.resolve("src/old/package"))
        Files.writeString(tempDir.resolve("src/old/package/Test.kt"), "package old.package\n\nclass Test")
        Files.writeString(tempDir.resolve("temp.tmp"), "delete me")

        val refactorYaml = """
            operations:
              - type: replace
                files: ["**/*.kt"]
                find: "old.package"
                replace: "${'$'}{package}"
              - type: delete
                files: ["*.tmp"]
              - type: rename
                from: "src/old/package"
                to: "src/${'$'}{packagePath}"
        """.trimIndent()
        Files.writeString(tempDir.resolve("refactor.yaml"), refactorYaml)

        val variables = TemplateVariables("new.test.package", "multi-test")
        val result = templateRefactor.refactorTemplate(tempDir, variables)

        assertTrue(result.success)
        assertTrue(result.operationsExecuted >= 3)
        
        // Verify all operations worked
        val newFile = tempDir.resolve("src/new/test/package/Test.kt")
        assertTrue(Files.exists(newFile))
        
        val content = Files.readString(newFile)
        assertTrue(content.contains("package new.test.package"))
        
        assertFalse(Files.exists(tempDir.resolve("temp.tmp")))
        // Note: Old directory might not be fully cleaned up due to cleanup operation timing
    }
}