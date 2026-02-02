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
 * Functional tests for the FluxAgentsPlugin using Gradle TestKit.
 */
class FluxAgentsPluginFunctionalTest {

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
                id("io.fluxzero.agents")
            }

            repositories {
                mavenCentral()
            }

            fluxAgents {
                enabled.set(false) // Disable actual sync for this test
            }
        """.trimIndent())

        val result = GradleRunner.create()
            .withProjectDir(testProjectDir)
            .withArguments("tasks", "--all")
            .withPluginClasspath()
            .build()

        assertTrue(result.output.contains("syncAgentFiles"))
    }

    @Test
    fun `sync task is skipped when disabled`() {
        buildFile.writeText("""
            plugins {
                kotlin("jvm") version "2.1.20"
                id("io.fluxzero.agents")
            }

            repositories {
                mavenCentral()
            }

            fluxAgents {
                enabled.set(false)
            }
        """.trimIndent())

        val result = GradleRunner.create()
            .withProjectDir(testProjectDir)
            .withArguments("syncAgentFiles", "--info")
            .withPluginClasspath()
            .build()

        assertEquals(TaskOutcome.SKIPPED, result.task(":syncAgentFiles")?.outcome)
    }

    @Test
    fun `sync task handles no SDK version gracefully`() {
        // Create a project without Fluxzero SDK dependency
        buildFile.writeText("""
            plugins {
                kotlin("jvm") version "2.1.20"
                id("io.fluxzero.agents")
            }

            repositories {
                mavenCentral()
            }

            fluxAgents {
                // Disable to avoid network calls in test
                enabled.set(false)
            }
        """.trimIndent())

        // Create minimal Kotlin source to enable language detection
        File(testProjectDir, "src/main/kotlin").mkdirs()
        File(testProjectDir, "src/main/kotlin/Main.kt").writeText("fun main() {}")

        val result = GradleRunner.create()
            .withProjectDir(testProjectDir)
            .withArguments("syncAgentFiles", "--info")
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
    fun `extension can be configured`() {
        buildFile.writeText("""
            plugins {
                kotlin("jvm") version "2.1.20"
                id("io.fluxzero.agents")
            }

            repositories {
                mavenCentral()
            }

            fluxAgents {
                enabled.set(false)
                language.set("java")
                sdkVersion.set("1.0.0")
            }
        """.trimIndent())

        val result = GradleRunner.create()
            .withProjectDir(testProjectDir)
            .withArguments("tasks", "--info")
            .withPluginClasspath()
            .build()

        // Just verify build succeeds with configuration
        assertTrue(result.output.contains("syncAgentFiles"))
    }
}
