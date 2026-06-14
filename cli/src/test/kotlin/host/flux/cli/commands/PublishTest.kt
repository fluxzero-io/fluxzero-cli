package host.flux.cli.commands

import com.github.ajalt.clikt.testing.test
import host.flux.publishing.ImagePublisher
import host.flux.publishing.ImagePublishResult
import host.flux.publishing.JavaImagePublishSpec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.Attributes
import java.util.jar.JarOutputStream
import java.util.jar.Manifest

class PublishTest {
    @Test
    fun `publishes Maven project using registry default and manifest main class`() {
        val projectDir = Files.createTempDirectory("fluxzero-cli-publish")
        writePom(projectDir)
        Files.createDirectories(projectDir.resolve("target/classes"))
        Files.createDirectories(projectDir.resolve("target/fluxzero-dependencies"))
        Files.writeString(projectDir.resolve("target/classes/App.class"), "compiled")
        Files.writeString(projectDir.resolve("target/fluxzero-dependencies/commons-lang3-3.14.0.jar"), "dependency")
        writeJarWithManifest(projectDir.resolve("target/demo-app-1.0.0.jar"))

        val publisher = CapturingPublisher()
        val command = Publish(publisher = publisher, processRunner = NoopProcessRunner)

        val result = command.test(
            listOf(
                "--project-dir", projectDir.toString(),
                "--skip-build",
                "--registry-token", "test-token",
                "--image-name", "demo-app",
                "--image-version", "1.0.0",
                "--application-id", "app-123"
            )
        )

        val spec = publisher.spec ?: error("Expected publish spec")
        assertEquals("registry.fluxzero.io", spec.registryHost)
        assertEquals("test-token", spec.registryToken)
        assertEquals("demo-app", spec.imageName)
        assertEquals("1.0.0", spec.imageVersion)
        assertEquals("app-123", spec.applicationId)
        assertEquals("com.example.Application", spec.mainClass)
        assertEquals(projectDir.resolve("target/classes"), spec.classesDirectory)
        assertEquals(listOf(projectDir.resolve("target/fluxzero-dependencies/commons-lang3-3.14.0.jar")), spec.releaseDependencies)
        assertEquals("com.example", spec.labels["io.fluxzero.maven.group-id"])
        assertEquals("demo-app", spec.labels["io.fluxzero.maven.artifact-id"])
        assertTrue(result.stdout.contains("Published registry.fluxzero.io/demo-app:1.0.0"))
    }

    private fun writePom(projectDir: Path) {
        Files.writeString(
            projectDir.resolve("pom.xml"),
            """
            <project>
              <modelVersion>4.0.0</modelVersion>
              <groupId>com.example</groupId>
              <artifactId>demo-app</artifactId>
              <version>1.0.0</version>
            </project>
            """.trimIndent()
        )
    }

    private fun writeJarWithManifest(jar: Path) {
        val manifest = Manifest()
        manifest.mainAttributes.put(Attributes.Name.MANIFEST_VERSION, "1.0")
        manifest.mainAttributes.put(Attributes.Name.MAIN_CLASS, "com.example.Application")
        val outputStream = ByteArrayOutputStream()
        JarOutputStream(outputStream, manifest).use { }
        Files.write(jar, outputStream.toByteArray())
    }

    private class CapturingPublisher : ImagePublisher {
        var spec: JavaImagePublishSpec? = null

        override fun publish(spec: JavaImagePublishSpec): ImagePublishResult {
            this.spec = spec
            return ImagePublishResult("registry.fluxzero.io/demo-app:1.0.0", "sha256:test")
        }
    }

    private object NoopProcessRunner : ProcessRunner {
        override fun run(command: List<String>, workingDirectory: Path) = Unit
    }
}
