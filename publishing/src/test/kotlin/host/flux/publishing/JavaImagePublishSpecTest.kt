package host.flux.publishing

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.nio.file.Files

class JavaImagePublishSpecTest {
    @Test
    fun rejectsPlainHttpRegistryHost() {
        val classesDirectory = Files.createTempDirectory("fluxzero-publish-classes")

        assertThrows(IllegalArgumentException::class.java) {
            JavaImagePublishSpec(
                registryHost = "http://localhost:8080",
                registryToken = "token",
                imageName = "service",
                imageVersion = "1.0.0",
                mainClass = "com.example.Application",
                classesDirectory = classesDirectory
            ).validate()
        }
    }
}
