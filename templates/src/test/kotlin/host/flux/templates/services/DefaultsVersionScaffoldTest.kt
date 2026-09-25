package host.flux.templates.services

import host.flux.templates.models.BuildSystem
import host.flux.templates.models.ScaffoldProject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.Properties
import kotlin.io.path.inputStream
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DefaultsVersionScaffoldTest {
    @TempDir
    lateinit var workspace: Path

    @Test
    fun `same packaged templates freeze the local generation date for every language and build system`() {
        // One instant falls on different calendar days in these zones. No dependency on the test runner's clock.
        val instant = Instant.parse("2026-09-24T23:30:00Z")
        val cases = listOf(
            Clock.fixed(instant, ZoneId.of("Europe/Amsterdam")) to "2026.09.25",
            Clock.fixed(instant, ZoneId.of("America/Los_Angeles")) to "2026.09.24",
            Clock.fixed(Instant.parse("2027-01-02T12:00:00Z"), ZoneId.of("UTC")) to "2027.01.02"
        )
        cases.forEachIndexed { index, (clock, expected) ->
            listOf("flux-basic-java", "flux-basic-kotlin").forEach { template ->
                listOf(BuildSystem.MAVEN, BuildSystem.GRADLE).forEach { build ->
                    val result = ScaffoldService(clock = clock).scaffoldProject(
                        ScaffoldProject(
                            template = template,
                            name = "date-$index-$template-${build.name.lowercase()}",
                            outputDir = workspace.toString(),
                            buildSystem = build
                        )
                    )
                    assertTrue(result.success, result.message)
                    val file = Path.of(result.outputPath!!).resolve("src/main/resources/fluxzero.properties")
                    val properties = Properties().apply { file.inputStream().use(::load) }
                    assertEquals(expected, properties.getProperty("fluxzero.defaults.version"))
                    assertFalse(file.readText().contains("@fluxzeroDefaultsVersion@"))
                }
            }
        }
    }

    @Test
    fun `later in-place generation cannot advance an existing project's defaults`() {
        val request = ScaffoldProject(
            template = "flux-basic-java", name = "existing", outputDir = workspace.toString(),
            buildSystem = BuildSystem.MAVEN, inPlace = true
        )
        val first = ScaffoldService(clock = Clock.fixed(Instant.parse("2026-09-25T12:00:00Z"), ZoneId.of("UTC")))
        assertTrue(first.scaffoldProject(request).success)
        val file = workspace.resolve("src/main/resources/fluxzero.properties")
        val original = file.readText()
        val later = ScaffoldService(clock = Clock.fixed(Instant.parse("2027-01-02T12:00:00Z"), ZoneId.of("UTC")))
        assertFalse(later.scaffoldProject(request).success)
        assertEquals(original, file.readText())
    }
}
