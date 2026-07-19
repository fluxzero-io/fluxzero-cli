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
    fun testDevMojoAndControlParametersExist() {
        val mojoClass = DevMojo::class.java
        val fields = mojoClass.declaredFields

        assertNotNull(fields.find { it.name == "devServerVersion" })
        assertNotNull(fields.find { it.name == "devMainClass" })
        assertNotNull(fields.find { it.name == "applications" })
        assertNotNull(fields.find { it.name == "environment" })
        assertNotNull(fields.find { it.name == "port" })
        assertNotNull(fields.find { it.name == "idp" })
        assertNotNull(fields.find { it.name == "frontendCommand" })
        assertNotNull(fields.find { it.name == "frontendDirectory" })
        assertNotNull(fields.find { it.name == "frontendSetupCommand" })
        assertNotNull(fields.find { it.name == "frontendEnabled" })
        assertNotNull(fields.find { it.name == "backendPaths" })
        assertNotNull(fields.find { it.name == "fastCompiler" })
        assertNotNull(fields.find { it.name == "skipDev" })
    }

    @Test
    fun testPublishPackageMetadataParameterExists() {
        val mojoClass = PublishPackageMojo::class.java
        val fields = mojoClass.declaredFields

        val applicationIdField = fields.find { it.name == "applicationId" }
        assertNotNull("applicationId parameter should exist for package metadata", applicationIdField)
    }

    @Test
    fun testPublishPackageRegistryHostParameterExists() {
        val mojoClass = PublishPackageMojo::class.java
        val fields = mojoClass.declaredFields

        val registryHostField = fields.find { it.name == "registryHost" }
        assertNotNull("registryHost parameter should exist for registry override", registryHostField)
    }

    @Test
    fun testPublishPackageRegistryUsernameParameterExists() {
        val mojoClass = PublishPackageMojo::class.java
        val fields = mojoClass.declaredFields

        val registryUsernameField = fields.find { it.name == "registryUsername" }
        assertNotNull("registryUsername parameter should exist for non-Fluxzero registries", registryUsernameField)
    }

    @Test
    fun testPublishPackageImagesTagsAndCredentialsParametersExist() {
        val mojoClass = PublishPackageMojo::class.java
        val fields = mojoClass.declaredFields

        assertNotNull("images parameter should exist for multi-registry publishing", fields.find { it.name == "images" })
        assertNotNull("tags parameter should exist for multi-tag publishing", fields.find { it.name == "tags" })
        assertNotNull("credentials parameter should exist for registry credentials", fields.find { it.name == "credentials" })
    }

    @Test
    fun testRegistryCredentialConfigurationFieldsExist() {
        val fields = RegistryCredentialConfiguration::class.java.declaredFields

        assertNotNull(fields.find { it.name == "registryHost" })
        assertNotNull(fields.find { it.name == "registryUsername" })
        assertNotNull(fields.find { it.name == "registryToken" })
        assertFalse(fields.any { it.name.endsWith("EnvironmentVariable") })
    }

    @Test
    fun testPublishPackageTeamIdParameterExists() {
        val mojoClass = PublishPackageMojo::class.java
        val fields = mojoClass.declaredFields

        val teamIdField = fields.find { it.name == "teamId" }
        assertNotNull("teamId parameter should exist for team-prefixed package paths", teamIdField)
    }

    @Test
    fun testPublishPackageBaseImageSourceParameterExists() {
        val mojoClass = PublishPackageMojo::class.java
        val fields = mojoClass.declaredFields

        val baseImageSourceField = fields.find { it.name == "baseImageSource" }
        assertNotNull("baseImageSource parameter should exist for local base image builds", baseImageSourceField)
    }

    @Test
    fun testPublishPackageJavaToolOptionsParameterExists() {
        val mojoClass = PublishPackageMojo::class.java
        val fields = mojoClass.declaredFields

        val javaToolOptionsField = fields.find { it.name == "javaToolOptions" }
        assertNotNull("javaToolOptions parameter should exist for JVM option overrides", javaToolOptionsField)
    }

    @Test
    fun testPublishPackageAllowDirtyParameterExists() {
        val mojoClass = PublishPackageMojo::class.java
        val fields = mojoClass.declaredFields

        val allowDirtyField = fields.find { it.name == "allowDirty" }
        assertNotNull("allowDirty parameter should exist for intentional dirty publishes", allowDirtyField)
    }

    @Test
    fun testPublishPackageRetryParametersExist() {
        val mojoClass = PublishPackageMojo::class.java
        val fields = mojoClass.declaredFields

        val publishAttemptsField = fields.find { it.name == "publishAttempts" }
        val publishRetryDelayMillisField = fields.find { it.name == "publishRetryDelayMillis" }
        assertNotNull("publishAttempts parameter should exist for registry blob-upload retries", publishAttemptsField)
        assertNotNull("publishRetryDelayMillis parameter should exist for registry blob-upload retries", publishRetryDelayMillisField)
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
