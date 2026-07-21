package host.flux.maven

import host.flux.publishing.JavaPackageRegistryCredential
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class RegistryIdentityResolverTest {
    @Test
    fun `discovers identity endpoint from registry bearer challenge`() {
        val requests = mutableListOf<RegistryIdentityHttpRequest>()
        val resolver = RegistryIdentityResolver { request ->
            requests += request
            when (requests.size) {
                1 -> RegistryIdentityHttpResponse(
                    statusCode = 401,
                    headers = mapOf(
                        "www-authenticate" to listOf(
                            "Basic realm=\"Registry\"",
                            "Bearer realm=\"https://api.dashboard.fluxzero.io/api/registry/token\"," +
                                "service=\"registry.fluxzero.io\""
                        )
                    )
                )

                2 -> RegistryIdentityHttpResponse(
                    statusCode = 200,
                    body = """{"organisationId":"958e1ee2f6c64facbc7765026a9a6e09"}"""
                )

                else -> error("Unexpected request")
            }
        }

        val organisationId = resolver.resolveOrganisationId(
            JavaPackageRegistryCredential("registry.fluxzero.io", password = "raw-github-oidc-token")
        )

        assertEquals("958e1ee2f6c64facbc7765026a9a6e09", organisationId)
        assertEquals("https://registry.fluxzero.io/v2/", requests[0].uri.toString())
        assertNull(requests[0].bearerToken)
        assertEquals(
            "https://api.dashboard.fluxzero.io/api/registry/identity",
            requests[1].uri.toString()
        )
        assertEquals("raw-github-oidc-token", requests[1].bearerToken)
    }

    @Test
    fun `rejects insecure or unrelated token realms before sending credentials`() {
        listOf(
            "http://api.dashboard.fluxzero.io/api/registry/token",
            "https://api.dashboard.fluxzero.io/oauth/token"
        ).forEach { realm ->
            val requests = mutableListOf<RegistryIdentityHttpRequest>()
            val resolver = RegistryIdentityResolver { request ->
                requests += request
                RegistryIdentityHttpResponse(
                    statusCode = 401,
                    headers = mapOf("WWW-Authenticate" to listOf("Bearer realm=\"$realm\""))
                )
            }

            assertFailsWith<RegistryIdentityException> {
                resolver.resolveOrganisationId(
                    JavaPackageRegistryCredential("registry.fluxzero.io", password = "secret-token")
                )
            }
            assertEquals(1, requests.size)
            assertNull(requests.single().bearerToken)
        }
    }

    @Test
    fun `reports missing identity without exposing credentials or response body`() {
        val secret = "raw-secret-token"
        val responseSecret = "secret-upstream-response"
        val resolver = RegistryIdentityResolver { request ->
            if (request.bearerToken == null) {
                RegistryIdentityHttpResponse(
                    statusCode = 401,
                    headers = mapOf(
                        "WWW-Authenticate" to listOf(
                            "Bearer realm=\"https://api.dashboard.fluxzero.io/api/registry/token\""
                        )
                    )
                )
            } else {
                RegistryIdentityHttpResponse(statusCode = 403, body = responseSecret)
            }
        }

        val exception = assertFailsWith<RegistryIdentityException> {
            resolver.resolveOrganisationId(
                JavaPackageRegistryCredential("registry.fluxzero.io", password = secret)
            )
        }

        assertTrue(exception.message.orEmpty().contains("HTTP 403"))
        assertFalse(exception.message.orEmpty().contains(secret))
        assertFalse(exception.message.orEmpty().contains(responseSecret))
    }
}
