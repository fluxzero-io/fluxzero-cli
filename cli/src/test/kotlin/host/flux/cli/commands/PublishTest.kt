package host.flux.cli.commands

import com.github.ajalt.clikt.testing.test
import host.flux.publishing.BaseImageSource
import host.flux.publishing.PackagePublisher
import host.flux.publishing.PackagePublishResult
import host.flux.publishing.JavaPackagePublishSpec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
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
                "--package-name", "demo-app",
                "--package-version", "1.0.0",
                "--application-id", "app-123"
            )
        )

        val spec = publisher.spec ?: error("Expected publish spec")
        assertEquals("registry.fluxzero.io", spec.registryHost)
        assertEquals("test-token", spec.registryToken)
        assertEquals("demo-app", spec.packageName)
        assertEquals("1.0.0", spec.packageVersion)
        assertEquals("app-123", spec.applicationId)
        assertEquals("com.example.Application", spec.mainClass)
        assertEquals(JavaPackagePublishSpec.DEFAULT_BASE_IMAGE, spec.baseImage)
        assertEquals(BaseImageSource.REGISTRY, spec.baseImageSource)
        val expectedJavaToolOptions = System.getenv("JAVA_TOOL_OPTIONS") ?: JavaPackagePublishSpec.DEFAULT_JAVA_TOOL_OPTIONS
        assertEquals(expectedJavaToolOptions, spec.javaToolOptions)
        assertEquals(projectDir.resolve("target/classes"), spec.classesDirectory)
        assertEquals(listOf(projectDir.resolve("target/fluxzero-dependencies/commons-lang3-3.14.0.jar")), spec.releaseDependencies)
        assertEquals("com.example", spec.labels["io.fluxzero.maven.group-id"])
        assertEquals("demo-app", spec.labels["io.fluxzero.maven.artifact-id"])
        assertTrue(result.stdout.contains("Published registry.fluxzero.io/demo-app:1.0.0"))
    }

    @Test
    fun `publishes Maven project with docker daemon base image source`() {
        val projectDir = Files.createTempDirectory("fluxzero-cli-publish-local-base")
        writePom(projectDir)
        Files.createDirectories(projectDir.resolve("target/classes"))
        Files.writeString(projectDir.resolve("target/classes/App.class"), "compiled")
        writeJarWithManifest(projectDir.resolve("target/demo-app-1.0.0.jar"))

        val publisher = CapturingPublisher()
        val command = Publish(publisher = publisher, processRunner = NoopProcessRunner)

        command.test(
            listOf(
                "--project-dir", projectDir.toString(),
                "--skip-build",
                "--registry-token", "test-token",
                "--package-name", "demo-app",
                "--package-version", "1.0.0",
                "--base-image", "local-base:dev",
                "--base-image-source", "docker-daemon"
            )
        )

        val spec = publisher.spec ?: error("Expected publish spec")
        assertEquals("local-base:dev", spec.baseImage)
        assertEquals(BaseImageSource.DOCKER_DAEMON, spec.baseImageSource)
    }

    @Test
    fun `publishes Maven project with custom Java tool options`() {
        val projectDir = Files.createTempDirectory("fluxzero-cli-publish-java-tool-options")
        writePom(projectDir)
        Files.createDirectories(projectDir.resolve("target/classes"))
        Files.writeString(projectDir.resolve("target/classes/App.class"), "compiled")
        writeJarWithManifest(projectDir.resolve("target/demo-app-1.0.0.jar"))

        val publisher = CapturingPublisher()
        val command = Publish(publisher = publisher, processRunner = NoopProcessRunner)

        command.test(
            listOf(
                "--project-dir", projectDir.toString(),
                "--skip-build",
                "--registry-token", "test-token",
                "--package-name", "demo-app",
                "--package-version", "1.0.0",
                "--java-tool-options", ""
            )
        )

        val spec = publisher.spec ?: error("Expected publish spec")
        assertEquals("", spec.javaToolOptions)
    }

    @Test
    fun `rejects docker daemon base image source without explicit base image`() {
        val projectDir = Files.createTempDirectory("fluxzero-cli-publish-local-base-missing")
        writePom(projectDir)
        Files.createDirectories(projectDir.resolve("target/classes"))
        Files.writeString(projectDir.resolve("target/classes/App.class"), "compiled")
        writeJarWithManifest(projectDir.resolve("target/demo-app-1.0.0.jar"))

        val command = Publish(publisher = CapturingPublisher(), processRunner = NoopProcessRunner)

        val exception = assertThrows(IllegalStateException::class.java) {
            command.test(
                listOf(
                    "--project-dir", projectDir.toString(),
                    "--skip-build",
                    "--registry-token", "test-token",
                    "--package-name", "demo-app",
                    "--package-version", "1.0.0",
                    "--base-image-source", "docker-daemon"
                )
            )
        }

        assertTrue(exception.message?.contains("Set --base-image") == true)
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

    private class CapturingPublisher : PackagePublisher {
        var spec: JavaPackagePublishSpec? = null

        override fun publish(spec: JavaPackagePublishSpec): PackagePublishResult {
            this.spec = spec
            return PackagePublishResult("registry.fluxzero.io/demo-app:1.0.0", "sha256:test")
        }
    }

    private object NoopProcessRunner : ProcessRunner {
        override fun run(command: List<String>, workingDirectory: Path) = Unit
    }
}
