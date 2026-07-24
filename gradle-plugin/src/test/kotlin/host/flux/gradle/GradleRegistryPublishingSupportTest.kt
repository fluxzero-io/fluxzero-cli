package host.flux.gradle

import host.flux.publishing.JavaPackageRegistryCredential
import host.flux.publishing.JavaPackagePublishSpec
import host.flux.publishing.PackagePublisher
import java.net.URI
import java.nio.file.Files
import java.util.jar.Attributes
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GradleRegistryPublishingSupportTest {
    @Test
    fun buildsPublishSpecFromGradleOutputsInClasspathOrder() {
        val project = ProjectBuilder.builder().build()
        project.pluginManager.apply("java")
        project.pluginManager.apply(FluxzeroPlugin::class.java)
        val task = project.tasks.named("fluxzeroPublishPackage", PublishPackageTask::class.java).get()
        val firstOutput = Files.createTempDirectory("fluxzero-gradle-java-output")
        val secondOutput = Files.createTempDirectory("fluxzero-gradle-kotlin-output")
        Files.createDirectories(firstOutput.resolve("com/example"))
        Files.createDirectories(secondOutput.resolve("com/example"))
        Files.writeString(firstOutput.resolve("com/example/JavaApp.class"), "java")
        Files.writeString(secondOutput.resolve("com/example/KotlinApp.class"), "kotlin")
        val firstDependency = Files.createTempFile("first-dependency", ".jar")
        val secondDependency = Files.createTempFile("second-dependency", ".jar")
        val artifact = Files.createTempFile("application", ".jar")
        JarOutputStream(
            Files.newOutputStream(artifact),
            Manifest().apply {
                mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
                mainAttributes[Attributes.Name.MAIN_CLASS] = "com.example.Application"
            }
        ).use { }
        var captured: JavaPackagePublishSpec? = null

        task.packageName.set("service")
        task.packageVersion.set("sha-example")
        task.images.set(listOf("registry.example.com/org/service"))
        task.tags.set(listOf("sha-example"))
        task.classesDirectories.setFrom(firstOutput, secondOutput)
        task.runtimeClasspath.setFrom(firstDependency, secondDependency)
        task.applicationArtifact.set(artifact.toFile())
        task.publisher = PackagePublisher { spec ->
            captured = spec
            emptyList()
        }

        task.publishPackage()

        assertNotNull(captured)
        val spec = captured!!
        assertEquals("com.example.Application", spec.mainClass)
        assertEquals(
            listOf(firstDependency, secondDependency),
            spec.dependencies.map { it.source }
        )
        assertEquals("java", Files.readString(spec.classesDirectory.resolve("com/example/JavaApp.class")))
        assertEquals("kotlin", Files.readString(spec.classesDirectory.resolve("com/example/KotlinApp.class")))
    }

    @Test
    fun resolvesTypedGitHubOidcAuthentication() {
        val extension = extension()
        extension.authentications.create("fluxzero") {
            it.host.set("registry.fluxzero.io")
            it.githubOidc { oidc ->
                oidc.username.set("github-ci")
                oidc.audience.set("https://cloud.fluxzero.io")
            }
        }

        val credentials = GradleRegistryAuthenticationSupport.resolve(extension.authentications) { audience ->
            assertEquals("https://cloud.fluxzero.io", audience)
            "github-oidc-token"
        }

        assertEquals(
            listOf(JavaPackageRegistryCredential("registry.fluxzero.io", "github-ci", "github-oidc-token")),
            credentials
        )
    }

    @Test
    fun requiresExactlyOneAuthenticationMechanism() {
        val extension = extension()
        extension.authentications.create("missing") {
            it.host.set("registry.fluxzero.io")
        }

        val failure = assertThrows(IllegalArgumentException::class.java) {
            GradleRegistryAuthenticationSupport.resolve(extension.authentications)
        }

        assertTrue(failure.message.orEmpty().contains("exactly one of basic or githubOidc"))
    }

    @Test
    fun resolvesImagePlaceholdersOncePerRegistryHost() {
        val credential = JavaPackageRegistryCredential("registry.fluxzero.io", password = "token")
        var identityRequests = 0

        val images = GradlePackageImageSupport.resolve(
            configuredImages = listOf(
                "registry.fluxzero.io/${'$'}{organisationId}/${'$'}{packageName}",
                "registry.fluxzero.io/${'$'}{organisationId}/worker"
            ),
            packageName = "service",
            credentials = listOf(credential)
        ) {
            identityRequests++
            "organisation-a"
        }

        assertEquals(
            listOf(
                "registry.fluxzero.io/organisation-a/service",
                "registry.fluxzero.io/organisation-a/worker"
            ),
            images
        )
        assertEquals(1, identityRequests)
    }

    @Test
    fun discoversIdentityEndpointWithoutSendingCredentialToRegistry() {
        val requests = mutableListOf<GradleRegistryIdentityHttpRequest>()
        val resolver = GradleRegistryIdentityResolver { request ->
            requests.add(request)
            if (requests.size == 1) {
                GradleRegistryIdentityHttpResponse(
                    statusCode = 401,
                    headers = mapOf(
                        "WWW-Authenticate" to listOf(
                            "Bearer realm=\"https://api.dashboard.fluxzero.io/api/registry/token\",service=\"registry.fluxzero.io\""
                        )
                    )
                )
            } else {
                GradleRegistryIdentityHttpResponse(
                    statusCode = 200,
                    body = """{"organisationId":"organisation-a"}"""
                )
            }
        }

        val organisationId = resolver.resolveOrganisationId(
            JavaPackageRegistryCredential("registry.fluxzero.io", password = "raw-token")
        )

        assertEquals("organisation-a", organisationId)
        assertEquals(URI.create("https://registry.fluxzero.io/v2/"), requests[0].uri)
        assertEquals(null, requests[0].bearerToken)
        assertEquals(
            URI.create("https://api.dashboard.fluxzero.io/api/registry/identity"),
            requests[1].uri
        )
        assertEquals("raw-token", requests[1].bearerToken)
    }

    @Test
    fun requestsGitHubOidcTokenForConfiguredAudience() {
        var requestedUri: URI? = null
        var requestedBearer: String? = null
        val resolver = GradleGitHubOidcTokenResolver(
            environment = mapOf(
                "ACTIONS_ID_TOKEN_REQUEST_URL" to "https://token.actions.example/oidc?job=1",
                "ACTIONS_ID_TOKEN_REQUEST_TOKEN" to "request-token"
            ),
            httpGet = { uri, bearer ->
                requestedUri = uri
                requestedBearer = bearer
                GradleOidcHttpResponse(200, """{"value":"oidc-token"}""")
            }
        )

        assertEquals("oidc-token", resolver.resolve("https://cloud.fluxzero.io/tenant a"))
        assertEquals(
            URI.create("https://token.actions.example/oidc?job=1&audience=https%3A%2F%2Fcloud.fluxzero.io%2Ftenant+a"),
            requestedUri
        )
        assertEquals("request-token", requestedBearer)
    }

    private fun extension(): PackagePublishingExtension =
        ProjectBuilder.builder().build().objects.newInstance(PackagePublishingExtension::class.java)
}
