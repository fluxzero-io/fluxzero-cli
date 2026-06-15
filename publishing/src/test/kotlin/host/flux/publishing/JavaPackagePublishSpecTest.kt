package host.flux.publishing

import com.google.cloud.tools.jib.api.buildplan.FileEntriesLayer
import java.nio.file.Files
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JavaPackagePublishSpecTest {
    @Test
    fun rejectsPlainHttpRegistryHost() {
        val classesDirectory = Files.createTempDirectory("fluxzero-publish-classes")

        assertThrows(IllegalArgumentException::class.java) {
            JavaPackagePublishSpec(
                registryHost = "http://localhost:8080",
                registryToken = "token",
                packageName = "service",
                packageVersion = "1.0.0",
                mainClass = "com.example.Application",
                classesDirectory = classesDirectory
            ).validate()
        }
    }

    @Test
    fun rejectsBlankBaseImage() {
        val classesDirectory = Files.createTempDirectory("fluxzero-publish-classes")

        assertThrows(IllegalArgumentException::class.java) {
            JavaPackagePublishSpec(
                registryHost = "registry.fluxzero.io",
                registryToken = "token",
                packageName = "service",
                packageVersion = "1.0.0",
                mainClass = "com.example.Application",
                baseImage = "",
                classesDirectory = classesDirectory
            ).validate()
        }
    }

    @Test
    fun parsesBaseImageSourceAliases() {
        assertEquals(BaseImageSource.REGISTRY, BaseImageSource.parse("registry"))
        assertEquals(BaseImageSource.DOCKER_DAEMON, BaseImageSource.parse("docker-daemon"))
        assertEquals(BaseImageSource.DOCKER_DAEMON, BaseImageSource.parse("docker_daemon"))
        assertEquals(BaseImageSource.DOCKER_DAEMON, BaseImageSource.parse("docker"))
    }

    @Test
    fun hasDefaultJavaToolOptions() {
        assertEquals("-XX:MaxRAMPercentage=75.0", JavaPackagePublishSpec.DEFAULT_JAVA_TOOL_OPTIONS.substringBefore(" "))
    }

    @Test
    fun buildsContainerPlanWithReproducibleTimestampsAndSortedApplicationEntries() {
        val classesDirectory = Files.createTempDirectory("fluxzero-publish-classes")
        Files.createDirectories(classesDirectory.resolve("z"))
        Files.writeString(classesDirectory.resolve("z/Z.class"), "compiled-z")
        Files.createDirectories(classesDirectory.resolve("a"))
        Files.writeString(classesDirectory.resolve("a/A.class"), "compiled-a")
        val dependency = Files.createTempFile("commons-lang3", ".jar")
        Files.writeString(dependency, "dependency")

        val plan = JavaPackagePublisher().buildPlan(
            JavaPackagePublishSpec(
                registryHost = "registry.fluxzero.io",
                registryToken = "token",
                packageName = "service",
                packageVersion = "1.0.0",
                mainClass = "com.example.Application",
                classesDirectory = classesDirectory,
                releaseDependencies = listOf(dependency)
            )
        )

        val fileEntriesLayers = plan.layers.filterIsInstance<FileEntriesLayer>()
        val entrypoint = plan.entrypoint
        val applicationEntries = fileEntriesLayers
            .first { it.name == "application" }
            .entries

        assertEquals(JavaPackagePublishSpec.REPRODUCIBLE_CONTAINER_TIMESTAMP, plan.creationTime)
        assertTrue(fileEntriesLayers.flatMap { it.entries }.all { it.modificationTime == JavaPackagePublishSpec.REPRODUCIBLE_FILE_TIMESTAMP })
        assertEquals(
            listOf("/app/classes/a/A.class", "/app/classes/z/Z.class"),
            applicationEntries.map { it.extractionPath.toString() }
        )
        assertEquals(listOf("java", "-cp", "/app/classes:/app/libs/*", "com.example.Application"), entrypoint)
    }
}
