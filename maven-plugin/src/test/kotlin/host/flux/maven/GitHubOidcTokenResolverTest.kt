package host.flux.maven

import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Test

class GitHubOidcTokenResolverTest {
    @Test
    fun `requires an explicit github oidc audience`() {
        listOf(null, "  ").forEach { configuredAudience ->
            val exception = assertFailsWith<IllegalArgumentException> {
                GitHubOidcAudience.resolve(configuredAudience)
            }
            assertTrue(exception.message.orEmpty().contains("<audience>"))
        }
        assertEquals("custom-audience", GitHubOidcAudience.resolve(" custom-audience "))
    }

    @Test
    fun `requests a github oidc token for the configured audience`() {
        var requestedUri: URI? = null
        var requestBearerToken: String? = null
        val resolver = GitHubOidcTokenResolver(
            environment = mapOf(
                "ACTIONS_ID_TOKEN_REQUEST_URL" to "https://token.actions.githubusercontent.com/oidc?api-version=2.0",
                "ACTIONS_ID_TOKEN_REQUEST_TOKEN" to "request-token"
            ),
            httpGet = OidcHttpGet { uri, bearerToken ->
                requestedUri = uri
                requestBearerToken = bearerToken
                OidcHttpResponse(200, """{"value":"header.payload.signature"}""")
            }
        )

        val token = resolver.resolve("https://cloud.fluxzero.io/repository a")

        assertEquals("header.payload.signature", token)
        assertEquals("request-token", requestBearerToken)
        assertEquals(
            URI.create(
                "https://token.actions.githubusercontent.com/oidc?api-version=2.0&" +
                    "audience=https%3A%2F%2Fcloud.fluxzero.io%2Frepository+a"
            ),
            requestedUri
        )
    }

    @Test
    fun `requires github injected request credentials`() {
        val exception = assertFailsWith<GitHubOidcException> {
            GitHubOidcTokenResolver(environment = emptyMap()).resolve("https://cloud.fluxzero.io")
        }

        assertTrue(exception.message.orEmpty().contains("ACTIONS_ID_TOKEN_REQUEST_URL"))
        assertTrue(exception.message.orEmpty().contains("id-token: write"))
    }

    @Test
    fun `rejects a non https github request url before sending its bearer token`() {
        var requestAttempted = false
        val resolver = GitHubOidcTokenResolver(
            environment = mapOf(
                "ACTIONS_ID_TOKEN_REQUEST_URL" to "http://token.actions.githubusercontent.com/oidc?api-version=2.0",
                "ACTIONS_ID_TOKEN_REQUEST_TOKEN" to "request-token"
            ),
            httpGet = OidcHttpGet { _, _ ->
                requestAttempted = true
                OidcHttpResponse(200, """{"value":"unused"}""")
            }
        )

        val exception = assertFailsWith<GitHubOidcException> {
            resolver.resolve("https://cloud.fluxzero.io")
        }

        assertEquals("GitHub's OIDC request URL must use HTTPS.", exception.message)
        assertEquals(false, requestAttempted)
    }

    @Test
    fun `reports github oidc request failures without exposing the request token`() {
        val resolver = GitHubOidcTokenResolver(
            environment = mapOf(
                "ACTIONS_ID_TOKEN_REQUEST_URL" to "https://token.actions.githubusercontent.com/oidc?api-version=2.0",
                "ACTIONS_ID_TOKEN_REQUEST_TOKEN" to "secret-request-token"
            ),
            httpGet = OidcHttpGet { _, _ -> OidcHttpResponse(403, "forbidden") }
        )

        val exception = assertFailsWith<GitHubOidcException> {
            resolver.resolve("https://cloud.fluxzero.io")
        }

        assertEquals("GitHub Actions rejected the OIDC token request with HTTP 403.", exception.message)
        assertTrue(!exception.message.orEmpty().contains("secret-request-token"))
    }
}
