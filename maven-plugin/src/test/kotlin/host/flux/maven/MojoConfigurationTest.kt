package host.flux.maven

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for SyncProjectFilesMojo configuration.
 */
class MojoConfigurationTest {

    @Test
    fun testMojoClassExists() {
        val mojoClass = SyncProjectFilesMojo::class.java
        assertNotNull("SyncProjectFilesMojo class should exist", mojoClass)
    }

    @Test
    fun testEnabledParameterExists() {
        val mojoClass = SyncProjectFilesMojo::class.java
        val fields = mojoClass.declaredFields
        val enabledField = fields.find { it.name == "enabled" }
        assertNotNull("enabled parameter should exist", enabledField)
    }

    @Test
    fun testSkipParameterExists() {
        val mojoClass = SyncProjectFilesMojo::class.java
        val fields = mojoClass.declaredFields
        val skipField = fields.find { it.name == "skip" }
        assertNotNull("skip parameter should exist for backward compatibility", skipField)
    }

    @Test
    fun testBothEnabledAndSkipExist() {
        val mojoClass = SyncProjectFilesMojo::class.java
        val fields = mojoClass.declaredFields

        val enabledExists = fields.any { it.name == "enabled" }
        val skipExists = fields.any { it.name == "skip" }

        assertTrue("enabled parameter must exist", enabledExists)
        assertTrue("skip parameter must exist for backward compatibility", skipExists)
    }

    @Test
    fun testOverrideParametersExist() {
        val mojoClass = SyncProjectFilesMojo::class.java
        val fields = mojoClass.declaredFields

        val overrideLanguageField = fields.find { it.name == "overrideLanguage" }
        assertNotNull("overrideLanguage parameter should exist", overrideLanguageField)

        val overrideSdkVersionField = fields.find { it.name == "overrideSdkVersion" }
        assertNotNull("overrideSdkVersion parameter should exist", overrideSdkVersionField)

        val forceUpdateField = fields.find { it.name == "forceUpdate" }
        assertNotNull("forceUpdate parameter should exist", forceUpdateField)
    }
}
