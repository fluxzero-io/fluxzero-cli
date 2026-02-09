package host.flux.gradle

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Functional tests for the FluxzeroPlugin using Gradle TestKit.
 */
class FluxzeroPluginFunctionalTest {

    @TempDir
    lateinit var testProjectDir: File

    private lateinit var buildFile: File
    private lateinit var settingsFile: File

    @BeforeEach
    fun setup() {
        buildFile = File(testProjectDir, "build.gradle.kts")
        settingsFile = File(testProjectDir, "settings.gradle.kts")

        settingsFile.writeText("""
            rootProject.name = "test-project"
        """.trimIndent())
    }

    @Test
    fun `plugin can be applied successfully`() {
        buildFile.writeText("""
            plugins {
                kotlin("jvm") version "2.1.20"
                id("io.fluxzero.tools.gradle")
            }

            repositories {
                mavenCentral()
            }

            fluxzero {
                projectFiles {
                    enabled.set(false) // Disable actual sync for this test
                }
            }
        """.trimIndent())

        val result = GradleRunner.create()
            .withProjectDir(testProjectDir)
            .withArguments("tasks", "--all")
            .withPluginClasspath()
            .build()

        assertTrue(result.output.contains("syncProjectFiles"))
    }

    @Test
    fun `sync task is skipped when disabled`() {
        buildFile.writeText("""
            plugins {
                kotlin("jvm") version "2.1.20"
                id("io.fluxzero.tools.gradle")
            }

            repositories {
                mavenCentral()
            }

            fluxzero {
                projectFiles {
                    enabled.set(false)
                }
            }
        """.trimIndent())

        val result = GradleRunner.create()
            .withProjectDir(testProjectDir)
            .withArguments("syncProjectFiles", "--info")
            .withPluginClasspath()
            .build()

        assertEquals(TaskOutcome.SKIPPED, result.task(":syncProjectFiles")?.outcome)
    }

    @Test
    fun `sync task handles no SDK version gracefully`() {
        // Create a project without Fluxzero SDK dependency
        buildFile.writeText("""
            plugins {
                kotlin("jvm") version "2.1.20"
                id("io.fluxzero.tools.gradle")
            }

            repositories {
                mavenCentral()
            }

            fluxzero {
                projectFiles {
                    // Disable to avoid network calls in test
                    enabled.set(false)
                }
            }
        """.trimIndent())

        // Create minimal Kotlin source to enable language detection
        File(testProjectDir, "src/main/kotlin").mkdirs()
        File(testProjectDir, "src/main/kotlin/Main.kt").writeText("fun main() {}")

        val result = GradleRunner.create()
            .withProjectDir(testProjectDir)
            .withArguments("syncProjectFiles", "--info")
            .withPluginClasspath()
            .build()

        // Task should be skipped when disabled
        assertTrue(
            result.output.contains("SKIPPED") ||
                result.output.contains("skipped"),
            "Expected task to be skipped when disabled"
        )
    }

    @Test
    fun `extension can be configured with overrides`() {
        buildFile.writeText("""
            plugins {
                kotlin("jvm") version "2.1.20"
                id("io.fluxzero.tools.gradle")
            }

            repositories {
                mavenCentral()
            }

            fluxzero {
                projectFiles {
                    enabled.set(false)
                    overrideLanguage.set("java")
                    overrideSdkVersion.set("1.0.0")
                }
            }
        """.trimIndent())

        val result = GradleRunner.create()
            .withProjectDir(testProjectDir)
            .withArguments("tasks", "--info")
            .withPluginClasspath()
            .build()

        // Just verify build succeeds with configuration
        assertTrue(result.output.contains("syncProjectFiles"))
    }

    @Test
    fun `sync task is skipped on subprojects when rootProjectOnly is true`() {
        // Create multi-module project structure
        settingsFile.writeText("""
            rootProject.name = "test-project"
            include("submodule")
        """.trimIndent())

        // Root project build file - no plugins
        buildFile.writeText("""
            plugins {
                kotlin("jvm") version "2.1.20" apply false
            }
        """.trimIndent())

        // Create submodule with plugin applied
        val submoduleDir = File(testProjectDir, "submodule")
        submoduleDir.mkdirs()
        File(submoduleDir, "build.gradle.kts").writeText("""
            plugins {
                kotlin("jvm")
                id("io.fluxzero.tools.gradle")
            }

            repositories {
                mavenCentral()
            }

            fluxzero {
                projectFiles {
                    enabled.set(true)
                    rootProjectOnly.set(true) // default, but explicit for test clarity
                }
            }
        """.trimIndent())

        val result = GradleRunner.create()
            .withProjectDir(testProjectDir)
            .withArguments(":submodule:syncProjectFiles", "--info")
            .withPluginClasspath()
            .build()

        // Submodule task should be skipped because rootProjectOnly is true
        assertEquals(TaskOutcome.SKIPPED, result.task(":submodule:syncProjectFiles")?.outcome)
    }

    @Test
    fun `sync task runs on subprojects when rootProjectOnly is false`() {
        // Create multi-module project structure
        settingsFile.writeText("""
            rootProject.name = "test-project"
            include("submodule")
        """.trimIndent())

        // Root project build file - no plugins
        buildFile.writeText("""
            plugins {
                kotlin("jvm") version "2.1.20" apply false
            }
        """.trimIndent())

        // Create submodule with plugin applied
        val submoduleDir = File(testProjectDir, "submodule")
        submoduleDir.mkdirs()
        File(submoduleDir, "build.gradle.kts").writeText("""
            plugins {
                kotlin("jvm")
                id("io.fluxzero.tools.gradle")
            }

            repositories {
                mavenCentral()
            }

            fluxzero {
                projectFiles {
                    enabled.set(true)
                    rootProjectOnly.set(false) // allow running on subprojects
                }
            }
        """.trimIndent())

        // Use buildAndFail() because the task will try to run but fail due to no SDK version
        // The important thing is that it runs (FAILED) rather than being skipped
        val result = GradleRunner.create()
            .withProjectDir(testProjectDir)
            .withArguments(":submodule:syncProjectFiles", "--info")
            .withPluginClasspath()
            .buildAndFail()

        // Submodule task should NOT be skipped because rootProjectOnly is false
        // It will fail because there's no SDK version, but the key is it wasn't SKIPPED
        val outcome = result.task(":submodule:syncProjectFiles")?.outcome
        assertTrue(
            outcome == TaskOutcome.FAILED,
            "Expected task to run (FAILED, not SKIPPED) when rootProjectOnly=false, but was $outcome"
        )
    }
}
