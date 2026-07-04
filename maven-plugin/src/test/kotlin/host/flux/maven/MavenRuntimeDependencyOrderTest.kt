package host.flux.maven

import java.nio.file.Files
import org.junit.Test
import kotlin.test.assertEquals

class MavenRuntimeClasspathOrderTest {
    @Test
    fun `keeps Maven runtime classpath order and filters non-jar elements`() {
        val dependenciesDirectory = Files.createTempDirectory("fluxzero-maven-runtime-order")
        val outputDirectory = Files.createDirectory(dependenciesDirectory.resolve("classes"))
        val secondDirect = dependenciesDirectory.resolve("second-direct.jar")
        val transitive = dependenciesDirectory.resolve("transitive.jar")
        val firstDirect = dependenciesDirectory.resolve("first-direct.jar")
        val pomDependency = dependenciesDirectory.resolve("pom-only.pom")
        Files.writeString(secondDirect, "jar")
        Files.writeString(transitive, "jar")
        Files.writeString(firstDirect, "jar")
        Files.writeString(pomDependency, "pom")

        val ordered = MavenRuntimeClasspathOrder.runtimeJars(
            listOf(outputDirectory, secondDirect, transitive, firstDirect, pomDependency)
                .map { it.toString() }
        )

        assertEquals(listOf(secondDirect, transitive, firstDirect), ordered)
    }
}
