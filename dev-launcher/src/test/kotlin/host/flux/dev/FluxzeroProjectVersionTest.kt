package host.flux.dev

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals

class FluxzeroProjectVersionTest {
    @TempDir
    lateinit var projectDirectory: Path

    @Test
    fun `detects property-backed BOM version`() {
        Files.writeString(
            projectDirectory.resolve("pom.xml"),
            """
            <project>
              <properties><fluxzero.version>1.215.0</fluxzero.version></properties>
              <dependencyManagement><dependencies><dependency>
                <groupId>io.fluxzero</groupId><artifactId>fluxzero-bom</artifactId>
                <version>${'$'}{fluxzero.version}</version>
              </dependency></dependencies></dependencyManagement>
            </project>
            """.trimIndent()
        )

        assertEquals("1.215.0", FluxzeroProjectVersion.detect(projectDirectory))
    }

    @Test
    fun `detects literal BOM version without a project property`() {
        Files.writeString(
            projectDirectory.resolve("pom.xml"),
            """
            <project><dependencyManagement><dependencies><dependency>
              <groupId>io.fluxzero</groupId><artifactId>fluxzero-bom</artifactId><version>2.0.1</version>
            </dependency></dependencies></dependencyManagement></project>
            """.trimIndent()
        )

        assertEquals("2.0.1", FluxzeroProjectVersion.detect(projectDirectory))
    }

    @Test
    fun `detects Gradle SDK version from property reference`() {
        Files.writeString(projectDirectory.resolve("gradle.properties"), "fluxzeroVersion=1.230.0\n")
        Files.writeString(
            projectDirectory.resolve("build.gradle.kts"),
            "dependencies { implementation(platform(\"io.fluxzero:fluxzero-bom:\$fluxzeroVersion\")) }"
        )

        assertEquals("1.230.0", FluxzeroProjectVersion.detect(projectDirectory))
    }

    @Test
    fun `detects Gradle SDK version from version catalog`() {
        Files.createDirectories(projectDirectory.resolve("gradle"))
        Files.writeString(projectDirectory.resolve("build.gradle.kts"), "plugins { java }")
        Files.writeString(projectDirectory.resolve("gradle/libs.versions.toml"), "fluxzero = \"1.231.0\"\n")

        assertEquals("1.231.0", FluxzeroProjectVersion.detect(projectDirectory))
    }
}
