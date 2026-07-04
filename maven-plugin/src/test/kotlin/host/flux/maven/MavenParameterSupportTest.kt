package host.flux.maven

import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals

class MavenParameterSupportTest {
    @Test
    fun `Maven user property overrides configured plugin value`() {
        val userProperties = Properties().apply {
            setProperty("fluxzero.package.mainClass", "com.example.CliMain")
        }

        assertEquals(
            "com.example.CliMain",
            MavenParameterSupport.firstConfigured(
                userProperties,
                "fluxzero.package.mainClass",
                "FLUXZERO_TEST_ENV_DOES_NOT_EXIST",
                "com.example.PomMain"
            )
        )
    }

    @Test
    fun `blank Maven user property falls back to configured plugin value`() {
        val userProperties = Properties().apply {
            setProperty("fluxzero.package.mainClass", " ")
        }

        assertEquals(
            "com.example.PomMain",
            MavenParameterSupport.firstConfigured(
                userProperties,
                "fluxzero.package.mainClass",
                "FLUXZERO_TEST_ENV_DOES_NOT_EXIST",
                "com.example.PomMain"
            )
        )
    }

    @Test
    fun `system property is used when Maven session is unavailable`() {
        System.setProperty("fluxzero.package.mainClass", "com.example.SystemMain")
        try {
            assertEquals(
                "com.example.SystemMain",
                MavenParameterSupport.firstConfigured(
                    null,
                    "fluxzero.package.mainClass",
                    "FLUXZERO_TEST_ENV_DOES_NOT_EXIST",
                    "com.example.PomMain"
                )
            )
        } finally {
            System.clearProperty("fluxzero.package.mainClass")
        }
    }

    @Test
    fun `configured value accepts blank Maven user property`() {
        val userProperties = Properties().apply {
            setProperty("fluxzero.package.javaToolOptions", "")
        }

        assertEquals(
            "",
            MavenParameterSupport.firstConfiguredValue(
                userProperties,
                "fluxzero.package.javaToolOptions",
                "JAVA_TOOL_OPTIONS",
                "pom-value"
            )
        )
    }
}
