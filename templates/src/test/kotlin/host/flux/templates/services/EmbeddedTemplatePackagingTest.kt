package host.flux.templates.services

import java.nio.file.Files
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EmbeddedTemplatePackagingTest {

    @Test
    fun `packages every customer template with the configured Fluxzero versions`() {
        val sdkVersion = System.getProperty("templateSdkVersion")
        val idpVersion = System.getProperty("templateIdpVersion")
        val pluginVersion = System.getProperty("templatePluginVersion")
        val service = ClasspathTemplateService()

        assertEquals(
            setOf("flux-basic-java", "flux-basic-kotlin"),
            service.listTemplates().map { it.name }.toSet()
        )

        service.listTemplates().forEach { template ->
            val target = Files.createTempDirectory("embedded-${template.name}")
            try {
                service.extractTemplate(template.name, target)
                val pom = target.resolve("pom.xml").readText()
                val gradleBuild = target.resolve("build.gradle.kts").readText()

                assertContains(pom, "<fluxzero.version>$sdkVersion</fluxzero.version>")
                assertContains(pom, "<fluxzero-idp.version>$idpVersion</fluxzero-idp.version>")
                assertContains(pom, "<version>$pluginVersion</version>")
                assertTrue(
                    pom.indexOf("<artifactId>spring-boot-dependencies</artifactId>") <
                            pom.indexOf("<artifactId>fluxzero-bom</artifactId>"),
                    "Spring Boot dependency management must take precedence in ${template.name}/pom.xml"
                )
                assertContains(gradleBuild, "val fluxzeroVersion = \"$sdkVersion\"")
                assertContains(gradleBuild, "val fluxzeroIdpVersion = \"$idpVersion\"")
                assertContains(gradleBuild, "version \"$pluginVersion\"")
                listOf("@fluxzeroSdkVersion@", "@fluxzeroIdpVersion@", "@fluxzeroPluginVersion@").forEach { token ->
                    assertFalse(pom.contains(token), "$token was not resolved in ${template.name}/pom.xml")
                    assertFalse(gradleBuild.contains(token), "$token was not resolved in ${template.name}/build.gradle.kts")
                }
                assertTrue(Files.exists(target.resolve(".gitignore")))
                assertTrue(Files.exists(target.resolve(".github/workflows/deploy-to-fluxzero-cloud.yml.maven")))
                assertTrue(Files.exists(target.resolve(".github/workflows/deploy-to-fluxzero-cloud.yml.gradle")))
            } finally {
                target.toFile().deleteRecursively()
            }
        }
    }
}
