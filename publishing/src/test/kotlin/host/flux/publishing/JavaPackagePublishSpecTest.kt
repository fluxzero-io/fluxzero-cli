package host.flux.publishing

import java.nio.file.Files
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
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
}
