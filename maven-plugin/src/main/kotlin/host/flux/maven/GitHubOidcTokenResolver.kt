package host.flux.maven

import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

internal class GitHubOidcTokenResolver(
    private val environment: Map<String, String> = System.getenv(),
    private val httpGet: OidcHttpGet = JdkOidcHttpGet()
) {
    fun resolve(audience: String): String {
        val requestUrl = requiredEnvironment("ACTIONS_ID_TOKEN_REQUEST_URL")
        val requestToken = requiredEnvironment("ACTIONS_ID_TOKEN_REQUEST_TOKEN")
        val uri = appendAudience(requestUrl, audience)
        if (!uri.scheme.equals("https", ignoreCase = true)) {
            throw GitHubOidcException("GitHub's OIDC request URL must use HTTPS.")
        }
        val response = try {
            httpGet.get(uri, requestToken)
        } catch (exception: Exception) {
            throw GitHubOidcException("Could not request a GitHub Actions OIDC token.", exception)
        }
        if (response.statusCode !in 200..299) {
            throw GitHubOidcException(
                "GitHub Actions rejected the OIDC token request with HTTP ${response.statusCode}."
            )
        }
        return tokenValue.find(response.body)?.groupValues?.get(1)
            ?: throw GitHubOidcException("GitHub's OIDC response did not contain a token value.")
    }

    private fun requiredEnvironment(name: String): String =
        environment[name]?.takeIf { it.isNotBlank() }
            ?: throw GitHubOidcException(
                "Missing required GitHub Actions environment variable $name. Grant 'id-token: write' to the job."
            )

    private fun appendAudience(requestUrl: String, audience: String): URI {
        val separator = when {
            requestUrl.endsWith("?") || requestUrl.endsWith("&") -> ""
            requestUrl.contains('?') -> "&"
            else -> "?"
        }
        val encodedAudience = URLEncoder.encode(audience, StandardCharsets.UTF_8)
        return try {
            URI.create("$requestUrl${separator}audience=$encodedAudience")
        } catch (exception: IllegalArgumentException) {
            throw GitHubOidcException("GitHub's OIDC request URL is invalid.", exception)
        }
    }

    companion object {
        private val tokenValue = Regex("\\\"value\\\"\\s*:\\s*\\\"([^\\\"\\\\]+)\\\"")
    }
}

internal object GitHubOidcAudience {
    fun resolve(configuredAudience: String?): String =
        configuredAudience?.trim()?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException(
                "Missing GitHub OIDC audience. Configure " +
                    "<authentications><authentication><host>...</host><github-oidc><audience>...</audience>" +
                    "</github-oidc></authentication></authentications>."
            )
}

internal fun interface OidcHttpGet {
    fun get(uri: URI, bearerToken: String): OidcHttpResponse
}

internal data class OidcHttpResponse(val statusCode: Int, val body: String)

private class JdkOidcHttpGet : OidcHttpGet {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()

    override fun get(uri: URI, bearerToken: String): OidcHttpResponse {
        val request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(30))
            .header("Authorization", "Bearer $bearerToken")
            .header("Accept", "application/json")
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        return OidcHttpResponse(response.statusCode(), response.body())
    }
}

internal class GitHubOidcException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
