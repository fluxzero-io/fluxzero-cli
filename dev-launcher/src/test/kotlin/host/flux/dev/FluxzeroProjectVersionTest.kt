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
}
