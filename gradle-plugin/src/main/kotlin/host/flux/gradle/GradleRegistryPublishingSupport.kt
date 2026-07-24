package host.flux.gradle

import host.flux.publishing.JavaPackageRegistryCredential
import host.flux.publishing.PackageNameSupport
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

internal object GradleRegistryAuthenticationSupport {
    fun resolve(
        authentications: Iterable<RegistryAuthentication>,
        githubToken: (String) -> String = { audience -> GradleGitHubOidcTokenResolver().resolve(audience) }
    ): List<JavaPackageRegistryCredential> {
        val credentials = authentications.map { authentication ->
            val host = authentication.host.orNull?.trim()?.takeIf(String::isNotBlank)
                ?: throw IllegalArgumentException(
                    "Missing host for registry authentication '${authentication.name}'."
                )
            require(PackageNameSupport.isValidRegistryHost(host)) {
                "Invalid registry authentication host '$host'. Configure a lowercase host with an optional port, " +
                    "without a scheme or path."
            }
            when (authentication.configuredMechanism) {
                AuthenticationMechanism.BASIC -> {
                    val basic = authentication.basicConfiguration
                    val token = basic.token.orNull?.takeIf(String::isNotBlank)
                        ?: throw IllegalArgumentException(
                            "Missing registry token for authentication '${authentication.name}'."
                        )
                    JavaPackageRegistryCredential(host, basic.username.orNull.orEmpty(), token)
                }

                AuthenticationMechanism.GITHUB_OIDC -> {
                    val githubOidc = authentication.githubOidcConfiguration
                    val audience = githubOidc.audience.orNull?.trim()?.takeIf(String::isNotBlank)
                        ?: throw IllegalArgumentException(
                            "Missing GitHub OIDC audience for authentication '${authentication.name}'."
                        )
                    JavaPackageRegistryCredential(
                        host,
                        githubOidc.username.orNull.orEmpty(),
                        githubToken(audience)
                    )
                }

                null -> throw IllegalArgumentException(
                    "Configure exactly one of basic or githubOidc for registry authentication '${authentication.name}'."
                )
            }
        }
        val duplicateHosts = credentials.groupBy { it.host }.filterValues { it.size > 1 }.keys
        require(duplicateHosts.isEmpty()) {
            "Configure exactly one registry authentication per host. Duplicate: ${duplicateHosts.sorted().joinToString()}."
        }
        return credentials
    }
}

internal object GradlePackageImageSupport {
    const val ORGANISATION_ID_PLACEHOLDER = "\${organisationId}"
    const val PACKAGE_NAME_PLACEHOLDER = "\${packageName}"

    fun resolve(
        configuredImages: Iterable<String>,
        packageName: String,
        credentials: List<JavaPackageRegistryCredential>,
        organisationId: (JavaPackageRegistryCredential) -> String = {
            GradleRegistryIdentityResolver().resolveOrganisationId(it)
        }
    ): List<String> {
        val images = configuredImages.map(String::trim).filter(String::isNotBlank)
        require(images.isNotEmpty()) { "Configure at least one package image." }
        val identities = mutableMapOf<String, String>()
        return images.map { configuredImage ->
            validatePlaceholder(configuredImage, PACKAGE_NAME_PLACEHOLDER)
            validatePlaceholder(configuredImage, ORGANISATION_ID_PLACEHOLDER)
            val withPackageName = configuredImage.replace(PACKAGE_NAME_PLACEHOLDER, packageName)
            if (!withPackageName.contains(ORGANISATION_ID_PLACEHOLDER)) {
                return@map withPackageName
            }
            val host = PackageNameSupport.registryAuthority(withPackageName)
            val credential = credentials.singleOrNull { it.host == host }
                ?: throw IllegalArgumentException(
                    "Image '$configuredImage' uses $ORGANISATION_ID_PLACEHOLDER but has no authentication " +
                        "for registry host '$host'."
                )
            val resolvedOrganisationId = identities.getOrPut(host) { organisationId(credential) }
            require(PackageNameSupport.isValidPackageName(resolvedOrganisationId)) {
                "Registry identity returned an invalid organisationId."
            }
            withPackageName.replace(ORGANISATION_ID_PLACEHOLDER, resolvedOrganisationId)
        }
    }

    private fun validatePlaceholder(image: String, placeholder: String) {
        if (!image.contains(placeholder)) {
            return
        }
        val pathSegments = image.substringAfter('/', "").split('/')
        require(pathSegments.count { it == placeholder } == 1 && image.countOccurrences(placeholder) == 1) {
            "$placeholder must occur exactly once as a complete image path segment: '$image'."
        }
    }

    private fun String.countOccurrences(value: String): Int = windowed(value.length).count { it == value }
}

internal class GradleGitHubOidcTokenResolver(
    private val environment: Map<String, String> = System.getenv(),
    private val httpGet: GradleOidcHttpGet = JdkGradleOidcHttpGet()
) {
    fun resolve(audience: String): String {
        val requestUrl = requiredEnvironment("ACTIONS_ID_TOKEN_REQUEST_URL")
        val requestToken = requiredEnvironment("ACTIONS_ID_TOKEN_REQUEST_TOKEN")
        val uri = appendAudience(requestUrl, audience)
        require(uri.scheme.equals("https", ignoreCase = true)) {
            "GitHub's OIDC request URL must use HTTPS."
        }
        val response = try {
            httpGet.get(uri, requestToken)
        } catch (exception: Exception) {
            throw IllegalArgumentException("Could not request a GitHub Actions OIDC token.", exception)
        }
        require(response.statusCode in 200..299) {
            "GitHub Actions rejected the OIDC token request with HTTP ${response.statusCode}."
        }
        return tokenValue.find(response.body)?.groupValues?.get(1)
            ?: throw IllegalArgumentException("GitHub's OIDC response did not contain a token value.")
    }

    private fun requiredEnvironment(name: String): String =
        environment[name]?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException(
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
            throw IllegalArgumentException("GitHub's OIDC request URL is invalid.", exception)
        }
    }

    companion object {
        private val tokenValue = Regex("\\\"value\\\"\\s*:\\s*\\\"([^\\\"\\\\]+)\\\"")
    }
}

internal fun interface GradleOidcHttpGet {
    fun get(uri: URI, bearerToken: String): GradleOidcHttpResponse
}

internal data class GradleOidcHttpResponse(val statusCode: Int, val body: String)

private class JdkGradleOidcHttpGet : GradleOidcHttpGet {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()

    override fun get(uri: URI, bearerToken: String): GradleOidcHttpResponse {
        val request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(30))
            .header("Authorization", "Bearer $bearerToken")
            .header("Accept", "application/json")
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        return GradleOidcHttpResponse(response.statusCode(), response.body())
    }
}

internal class GradleRegistryIdentityResolver(
    private val httpGet: GradleRegistryIdentityHttpGet = JdkGradleRegistryIdentityHttpGet()
) {
    fun resolveOrganisationId(credential: JavaPackageRegistryCredential): String {
        val challengeResponse = request(
            GradleRegistryIdentityHttpRequest(URI.create("https://${credential.host}/v2/"))
        )
        val identityUri = identityUri(challengeResponse)
        val identityResponse = request(
            GradleRegistryIdentityHttpRequest(identityUri, credential.password)
        )
        require(identityResponse.statusCode in 200..299) {
            "Registry identity request failed with HTTP ${identityResponse.statusCode}. " +
                "Configure the image explicitly if this registry does not expose Fluxzero registry identity."
        }
        return organisationId.find(identityResponse.body)?.groupValues?.get(1)
            ?: throw IllegalArgumentException(
                "Registry identity response did not contain organisationId. " +
                    "Configure the image explicitly if this registry does not expose Fluxzero registry identity."
            )
    }

    private fun request(request: GradleRegistryIdentityHttpRequest): GradleRegistryIdentityHttpResponse = try {
        httpGet.get(request)
    } catch (exception: IllegalArgumentException) {
        throw exception
    } catch (exception: Exception) {
        throw IllegalArgumentException("Could not resolve the registry identity.", exception)
    }

    private fun identityUri(response: GradleRegistryIdentityHttpResponse): URI {
        val challenge = response.headers.entries
            .firstOrNull { it.key.equals("WWW-Authenticate", ignoreCase = true) }
            ?.value
            ?.firstNotNullOfOrNull { bearerRealm.find(it)?.groupValues?.get(1) }
            ?: throw IllegalArgumentException(
                "Registry did not advertise a Docker Bearer token realm. " +
                    "Configure the image explicitly if this registry does not expose Fluxzero registry identity."
            )
        val realm = try {
            URI.create(challenge)
        } catch (exception: IllegalArgumentException) {
            throw IllegalArgumentException("Registry advertised an invalid Docker Bearer token realm.", exception)
        }
        require(realm.scheme.equals("https", ignoreCase = true) && !realm.host.isNullOrBlank() && realm.userInfo == null) {
            "Registry identity endpoint must be advertised through an HTTPS token realm."
        }
        val realmPath = realm.path.orEmpty().removeSuffix("/")
        require(realmPath.endsWith("/api/registry/token")) {
            "Registry token realm does not expose the Fluxzero /api/registry/token convention. " +
                "Configure the image explicitly."
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

internal fun interface GradleRegistryIdentityHttpGet {
    fun get(request: GradleRegistryIdentityHttpRequest): GradleRegistryIdentityHttpResponse
}

internal data class GradleRegistryIdentityHttpRequest(
    val uri: URI,
    val bearerToken: String? = null
)

internal data class GradleRegistryIdentityHttpResponse(
    val statusCode: Int,
    val body: String = "",
    val headers: Map<String, List<String>> = emptyMap()
)

private class JdkGradleRegistryIdentityHttpGet : GradleRegistryIdentityHttpGet {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()

    override fun get(request: GradleRegistryIdentityHttpRequest): GradleRegistryIdentityHttpResponse {
        val builder = HttpRequest.newBuilder(request.uri)
            .timeout(Duration.ofSeconds(30))
            .header("Accept", "application/json")
            .GET()
        request.bearerToken?.let { builder.header("Authorization", "Bearer $it") }
        val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        return GradleRegistryIdentityHttpResponse(response.statusCode(), response.body(), response.headers().map())
    }
}
