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
    fun `packages embedded templates with the configured plugin version`() {
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

                assertContains(pom, "<version>$pluginVersion</version>")
                assertContains(gradleBuild, "version \"$pluginVersion\"")
                assertFalse(pom.contains("@fluxzeroPluginVersion@"))
                assertFalse(gradleBuild.contains("@fluxzeroPluginVersion@"))
                assertTrue(Files.exists(target.resolve(".github/workflows/deploy-to-fluxzero-cloud.yml.maven")))
                assertTrue(Files.exists(target.resolve(".github/workflows/deploy-to-fluxzero-cloud.yml.gradle")))
            } finally {
                target.toFile().deleteRecursively()
            }
        }
    }
}
