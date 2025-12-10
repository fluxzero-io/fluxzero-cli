package host.flux.templates.refactor

import host.flux.templates.models.BuildSystem
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/**
 * Tests for build system-specific file deletion during refactoring
 */
class BuildSystemRefactorTest {

    private lateinit var tempDir: Path
    private val templateRefactor = TemplateRefactor()

    @BeforeTest
    fun setup() {
        tempDir = Files.createTempDirectory("build-system-test")
    }

    @AfterTest
    fun cleanup() {
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun `should delete Gradle files when Maven is selected`() {
        // Create template with both build files
        createBothBuildFiles()
        createGradleDirectory()
        createNestedGradleFiles()
        createSimpleRefactorYaml()

        val variables = TemplateVariables(
            packageName = "com.maven.test",
            projectName = "maven-test",
            buildSystem = BuildSystem.MAVEN
        )

        val result = templateRefactor.refactorTemplate(tempDir, variables)

        assertTrue(result.success)

        // Maven files should remain
        assertTrue(Files.exists(tempDir.resolve("pom.xml")))

        // Gradle files should be deleted (including nested ones)
        assertFalse(Files.exists(tempDir.resolve("build.gradle.kts")))
        assertFalse(Files.exists(tempDir.resolve("settings.gradle.kts")))
        assertFalse(Files.exists(tempDir.resolve("submodule/build.gradle.kts")))
        assertFalse(Files.exists(tempDir.resolve("submodule/custom.gradle")))
        // Gradle directory should be deleted
        assertFalse(Files.exists(tempDir.resolve("gradle")))
    }

    @Test
    fun `should delete Maven files when Gradle is selected`() {
        // Create template with both build files
        createBothBuildFiles()
        createMavenWrapperFiles()
        createNestedMavenFiles()
        createSimpleRefactorYaml()

        val variables = TemplateVariables(
            packageName = "com.gradle.test",
            projectName = "gradle-test",
            buildSystem = BuildSystem.GRADLE
        )

        val result = templateRefactor.refactorTemplate(tempDir, variables)

        assertTrue(result.success)

        // Gradle files should remain
        assertTrue(Files.exists(tempDir.resolve("build.gradle.kts")))
        assertTrue(Files.exists(tempDir.resolve("settings.gradle.kts")))

        // Maven files and wrapper should be deleted (including nested ones)
        assertFalse(Files.exists(tempDir.resolve("pom.xml")))
        assertFalse(Files.exists(tempDir.resolve("submodule/pom.xml")))
        assertFalse(Files.exists(tempDir.resolve("mvnw")))
        assertFalse(Files.exists(tempDir.resolve("mvnw.cmd")))
        assertFalse(Files.exists(tempDir.resolve(".mvn")))
    }

    @Test
    fun `should not delete any build files when buildSystem is null`() {
        // Create template with both build files
        createBothBuildFiles()
        createSimpleRefactorYaml()

        val variables = TemplateVariables(
            packageName = "com.no.build.system",
            projectName = "no-build-test",
            buildSystem = null
        )

        val result = templateRefactor.refactorTemplate(tempDir, variables)

        assertTrue(result.success)
        
        // Both build files should remain
        assertTrue(Files.exists(tempDir.resolve("pom.xml")))
        assertTrue(Files.exists(tempDir.resolve("build.gradle.kts")))
        assertTrue(Files.exists(tempDir.resolve("settings.gradle.kts")))
    }

    @Test
    fun `should handle missing build files gracefully`() {
        // Create template with only one build file
        Files.writeString(tempDir.resolve("pom.xml"), "<project></project>")
        createSimpleRefactorYaml()

        val variables = TemplateVariables(
            packageName = "com.missing.files",
            projectName = "missing-test",
            buildSystem = BuildSystem.GRADLE
        )

        val result = templateRefactor.refactorTemplate(tempDir, variables)

        assertTrue(result.success)
        
        // Should succeed even though Gradle files don't exist to delete
        assertFalse(Files.exists(tempDir.resolve("pom.xml"))) // Maven file should be deleted
        assertFalse(Files.exists(tempDir.resolve("build.gradle.kts"))) // Never existed
    }

    @Test
    fun `should include build system operations in operation count`() {
        createBothBuildFiles()
        createRefactorYamlWithMultipleOperations()

        val variables = TemplateVariables(
            packageName = "com.count.test",
            projectName = "count-test",
            buildSystem = BuildSystem.MAVEN
        )

        val result = templateRefactor.refactorTemplate(tempDir, variables)

        assertTrue(result.success)
        // Should count: 2 replace operations + 1 build system delete operation + 1 cleanup = 4
        assertTrue(result.operationsExecuted >= 4, "Expected at least 4 operations, got ${result.operationsExecuted}")
    }

    private fun createBothBuildFiles() {
        Files.writeString(tempDir.resolve("pom.xml"), """
            <?xml version="1.0" encoding="UTF-8"?>
            <project>
                <groupId>com.example.test</groupId>
                <artifactId>test-project</artifactId>
                <version>1.0-SNAPSHOT</version>
            </project>
        """.trimIndent())

        Files.writeString(tempDir.resolve("build.gradle.kts"), """
            group = "com.example.test"
            version = "1.0-SNAPSHOT"
            
            plugins {
                kotlin("jvm")
            }
        """.trimIndent())

        Files.writeString(tempDir.resolve("settings.gradle.kts"), """
            rootProject.name = "test-project"
        """.trimIndent())
    }

    private fun createSimpleRefactorYaml() {
        val refactorYaml = """
            operations:
              - type: replace
                files: ["**/*.xml"]
                find: "com.example.test"
                replace: "${'$'}{package}"
        """.trimIndent()
        Files.writeString(tempDir.resolve("refactor.yaml"), refactorYaml)
    }

    private fun createRefactorYamlWithMultipleOperations() {
        val refactorYaml = """
            operations:
              - type: replace
                files: ["**/*.xml"]
                find: "com.example.test"
                replace: "${'$'}{package}"
              - type: replace
                files: ["**/*.kts"]
                find: "com.example.test"
                replace: "${'$'}{package}"
        """.trimIndent()
        Files.writeString(tempDir.resolve("refactor.yaml"), refactorYaml)
    }

    private fun createGradleDirectory() {
        val gradleDir = tempDir.resolve("gradle")
        Files.createDirectories(gradleDir)
        val wrapperDir = gradleDir.resolve("wrapper")
        Files.createDirectories(wrapperDir)
        Files.writeString(wrapperDir.resolve("gradle-wrapper.properties"), """
            distributionUrl=https://services.gradle.org/distributions/gradle-8.5-bin.zip
        """.trimIndent())
        Files.writeString(wrapperDir.resolve("gradle-wrapper.jar"), "dummy jar content")
    }

    private fun createMavenWrapperFiles() {
        // Create Maven wrapper files
        Files.writeString(tempDir.resolve("mvnw"), "#!/bin/sh\n# Maven wrapper script")
        Files.writeString(tempDir.resolve("mvnw.cmd"), "@REM Maven wrapper batch script")

        // Create .mvn directory with wrapper
        val mvnDir = tempDir.resolve(".mvn")
        Files.createDirectories(mvnDir)
        val wrapperDir = mvnDir.resolve("wrapper")
        Files.createDirectories(wrapperDir)
        Files.writeString(wrapperDir.resolve("maven-wrapper.properties"), """
            distributionUrl=https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.4/apache-maven-3.9.4-bin.zip
        """.trimIndent())
        Files.writeString(wrapperDir.resolve("maven-wrapper.jar"), "dummy jar content")
    }

    private fun createNestedGradleFiles() {
        // Create submodule directory with gradle files
        val submoduleDir = tempDir.resolve("submodule")
        Files.createDirectories(submoduleDir)
        Files.writeString(submoduleDir.resolve("build.gradle.kts"), """
            plugins {
                kotlin("jvm")
            }
        """.trimIndent())
        // Also create a .gradle file (non-kts)
        Files.writeString(submoduleDir.resolve("custom.gradle"), """
            // Custom gradle script
            task hello { println "Hello" }
        """.trimIndent())
    }

    private fun createNestedMavenFiles() {
        // Create submodule directory with pom.xml
        val submoduleDir = tempDir.resolve("submodule")
        Files.createDirectories(submoduleDir)
        Files.writeString(submoduleDir.resolve("pom.xml"), """
            <?xml version="1.0" encoding="UTF-8"?>
            <project>
                <parent>
                    <groupId>com.example.test</groupId>
                    <artifactId>test-project</artifactId>
                    <version>1.0-SNAPSHOT</version>
                </parent>
                <artifactId>submodule</artifactId>
            </project>
        """.trimIndent())
    }
}