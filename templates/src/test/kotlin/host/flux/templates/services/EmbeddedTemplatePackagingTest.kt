package host.flux.templates.services

import host.flux.templates.models.BuildSystem
import host.flux.templates.models.ScaffoldProject
import java.nio.file.Path
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
                assertContains(pom, "<artifactId>client-bff</artifactId>")
                assertContains(gradleBuild, "io.fluxzero.idp:client-bff:")
                assertEquals("com.example.app.authentication.AppOidcUserMapper", target.resolve(
                    "src/main/resources/META-INF/services/io.fluxzero.idp.bff.OidcUserMapper").readText().trim())
                assertTrue(Files.exists(target.resolve(".gitignore")))
                assertTrue(Files.exists(target.resolve(".github/workflows/deploy-to-fluxzero-cloud.yml.maven")))
                assertTrue(Files.exists(target.resolve(".github/workflows/deploy-to-fluxzero-cloud.yml.gradle")))
            } finally {
                target.toFile().deleteRecursively()
            }
        }
    }
    @Test
    fun `scaffolding keeps mapper discovery aligned with the chosen package`() {
        val workspace = Files.createTempDirectory("mapper-scaffold")
        try {
            listOf("java", "kotlin").forEach { language ->
                BuildSystem.entries.forEach { build ->
                    val result = ScaffoldService().scaffoldProject(ScaffoldProject(
                        template = "flux-basic-$language", name = "$language-${build.name.lowercase()}",
                        outputDir = workspace.toString(), packageName = "com.example.customer", buildSystem = build
                    ))
                    assertTrue(result.success, result.message)
                    val root = Path.of(result.outputPath!!)
                    assertEquals("com.example.customer.authentication.AppOidcUserMapper", root.resolve(
                        "src/main/resources/META-INF/services/io.fluxzero.idp.bff.OidcUserMapper").readText().trim())
                    val extension = if (language == "java") "java" else "kt"
                    assertTrue(Files.exists(root.resolve(
                        "src/main/$language/com/example/customer/authentication/AppOidcUserMapper.$extension")))
                }
            }
        } finally {
            workspace.toFile().deleteRecursively()
        }
    }

}
