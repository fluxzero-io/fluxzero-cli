package host.flux.maven

import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals

class MavenParameterSupportTest {
    @Test
    fun `Maven user property overrides configured plugin value`() {
        val userProperties = Properties().apply {
            setProperty("fluxzero.image.name", "cli-image")
        }

        assertEquals(
            "cli-image",
            MavenParameterSupport.firstConfigured(
                userProperties,
                "fluxzero.image.name",
                "FLUXZERO_TEST_ENV_DOES_NOT_EXIST",
                "pom-image"
            )
        )
    }

    @Test
    fun `blank Maven user property falls back to configured plugin value`() {
        val userProperties = Properties().apply {
            setProperty("fluxzero.image.name", " ")
        }

        assertEquals(
            "pom-image",
            MavenParameterSupport.firstConfigured(
                userProperties,
                "fluxzero.image.name",
                "FLUXZERO_TEST_ENV_DOES_NOT_EXIST",
                "pom-image"
            )
        )
    }

    @Test
    fun `system property is used when Maven session is unavailable`() {
        System.setProperty("fluxzero.image.name", "system-image")
        try {
            assertEquals(
                "system-image",
                MavenParameterSupport.firstConfigured(
                    null,
                    "fluxzero.image.name",
                    "FLUXZERO_TEST_ENV_DOES_NOT_EXIST",
                    "pom-image"
                )
            )
        } finally {
            System.clearProperty("fluxzero.image.name")
        }
    }
}
