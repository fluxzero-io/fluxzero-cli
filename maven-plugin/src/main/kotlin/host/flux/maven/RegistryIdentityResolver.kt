package host.flux.maven

import host.flux.publishing.JavaPackageRegistryCredential
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

internal class RegistryIdentityResolver(
    private val httpGet: RegistryIdentityHttpGet = JdkRegistryIdentityHttpGet()
) {
    fun resolveOrganisationId(credential: JavaPackageRegistryCredential): String {
        val challengeResponse = request(
            RegistryIdentityHttpRequest(URI.create("https://${credential.host}/v2/"))
        )
        val identityUri = identityUri(challengeResponse)
        val identityResponse = request(
            RegistryIdentityHttpRequest(identityUri, credential.password)
        )
        if (identityResponse.statusCode !in 200..299) {
            throw RegistryIdentityException(
                "Registry identity request failed with HTTP ${identityResponse.statusCode}. " +
                    "Configure the image explicitly if this registry does not expose Fluxzero registry identity."
            )
        }
        return organisationId.find(identityResponse.body)?.groupValues?.get(1)
            ?: throw RegistryIdentityException(
                "Registry identity response did not contain organisationId. " +
                    "Configure the image explicitly if this registry does not expose Fluxzero registry identity."
            )
    }

    private fun request(request: RegistryIdentityHttpRequest): RegistryIdentityHttpResponse = try {
        httpGet.get(request)
    } catch (exception: RegistryIdentityException) {
        throw exception
    } catch (exception: Exception) {
        throw RegistryIdentityException("Could not resolve the registry identity.", exception)
    }

    private fun identityUri(response: RegistryIdentityHttpResponse): URI {
        val challenge = response.headers.entries
            .firstOrNull { it.key.equals("WWW-Authenticate", ignoreCase = true) }
            ?.value
            ?.firstNotNullOfOrNull { bearerRealm.find(it)?.groupValues?.get(1) }
            ?: throw RegistryIdentityException(
                "Registry did not advertise a Docker Bearer token realm. " +
                    "Configure the image explicitly if this registry does not expose Fluxzero registry identity."
            )
        val realm = try {
            URI.create(challenge)
        } catch (exception: IllegalArgumentException) {
            throw RegistryIdentityException("Registry advertised an invalid Docker Bearer token realm.", exception)
        }
        if (!realm.scheme.equals("https", ignoreCase = true) || realm.host.isNullOrBlank() || realm.userInfo != null) {
            throw RegistryIdentityException("Registry identity endpoint must be advertised through an HTTPS token realm.")
        }
        val realmPath = realm.path.orEmpty().removeSuffix("/")
        if (!realmPath.endsWith("/api/registry/token")) {
            throw RegistryIdentityException(
                "Registry token realm does not expose the Fluxzero /api/registry/token convention. " +
                    "Configure the image explicitly."
            )
        }
        return URI(
            realm.scheme,
            null,
            realm.host,
            realm.port,
            realmPath.removeSuffix("/token") + "/identity",
            null,
            null
        )
    }

    companion object {
        private val bearerRealm = Regex("""(?i)\bBearer\s+.*?\brealm\s*=\s*"([^"]+)"""")
        private val organisationId = Regex(""""organisationId"\s*:\s*"([^"\\]+)"""")
    }
}

internal fun interface RegistryIdentityHttpGet {
    fun get(request: RegistryIdentityHttpRequest): RegistryIdentityHttpResponse
}

internal data class RegistryIdentityHttpRequest(
    val uri: URI,
    val bearerToken: String? = null
)

internal data class RegistryIdentityHttpResponse(
    val statusCode: Int,
    val body: String = "",
    val headers: Map<String, List<String>> = emptyMap()
)

private class JdkRegistryIdentityHttpGet : RegistryIdentityHttpGet {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()

    override fun get(request: RegistryIdentityHttpRequest): RegistryIdentityHttpResponse {
        val builder = HttpRequest.newBuilder(request.uri)
            .timeout(Duration.ofSeconds(30))
            .header("Accept", "application/json")
            .GET()
        request.bearerToken?.let { builder.header("Authorization", "Bearer $it") }
        val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        return RegistryIdentityHttpResponse(response.statusCode(), response.body(), response.headers().map())
    }
}

internal class RegistryIdentityException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
