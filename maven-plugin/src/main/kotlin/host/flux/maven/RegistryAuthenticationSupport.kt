package host.flux.maven

import host.flux.publishing.JavaPackageRegistryCredential
import host.flux.publishing.PackageNameSupport

internal object RegistryAuthenticationSupport {
    fun resolve(
        authentications: List<Authentication>,
        githubToken: (String) -> String
    ): List<JavaPackageRegistryCredential> {
        val plans = authentications.mapIndexed { index, authentication ->
            validateAuthentication(index, authentication)
        }
        val duplicateHosts = plans
            .groupBy { it.host }
            .filterValues { it.size > 1 }
            .keys
        require(duplicateHosts.isEmpty()) {
            "Configure exactly one registry authentication per host. Duplicate: ${duplicateHosts.sorted().joinToString()}."
        }
        return plans.map { plan ->
            when (plan) {
                is BasicAuthenticationPlan -> JavaPackageRegistryCredential(plan.host, plan.username, plan.token)
                is GitHubOidcAuthenticationPlan -> JavaPackageRegistryCredential(
                    plan.host,
                    plan.username,
                    githubToken(plan.audience)
                )
            }
        }
    }

    private fun validateAuthentication(
        index: Int,
        configuration: Authentication
    ): RegistryAuthenticationPlan {
        val host = configuration.host?.trim()?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Missing <host> for registry authentication ${index + 1}.")
        require(PackageNameSupport.isValidRegistryHost(host)) {
            "Invalid registry authentication host '$host'. Configure a lowercase host with an optional port, without a scheme or path."
        }
        val mechanisms = listOfNotNull(configuration.basic, configuration.githubOidc)
        if (mechanisms.size != 1) {
            throw IllegalArgumentException(
                "Configure exactly one of <basic> or <github-oidc> for ${authenticationDescription(index, host)}."
            )
        }

        return when (val mechanism = mechanisms.single()) {
            is BasicAuthenticationConfiguration -> {
                val username = mechanism.username?.trim()?.takeIf { it.isNotBlank() }.orEmpty()
                val token = mechanism.token.nonBlank() ?: missingBasicToken(index, host)
                BasicAuthenticationPlan(host, username, token)
            }

            is GitHubOidcAuthenticationConfiguration -> GitHubOidcAuthenticationPlan(
                host = host,
                username = mechanism.username?.trim()?.takeIf { it.isNotBlank() }.orEmpty(),
                audience = GitHubOidcAudience.resolve(mechanism.audience)
            )

            else -> error("Unsupported registry authentication configuration ${mechanism::class.java.name}")
        }
    }

    private fun missingBasicToken(index: Int, host: String): Nothing {
        throw IllegalArgumentException(
            "Missing registry token for ${authenticationDescription(index, host)}. Configure " +
                "<authentications><authentication><host>...</host><basic><token>...</token></basic>" +
                "</authentication></authentications>."
        )
    }

    private fun authenticationDescription(index: Int, host: String): String =
        "registry authentication ${index + 1} ($host)"

    private fun String?.nonBlank(): String? = this?.takeIf { it.isNotBlank() }

    private sealed interface RegistryAuthenticationPlan {
        val host: String
    }

    private data class BasicAuthenticationPlan(
        override val host: String,
        val username: String,
        val token: String
    ) : RegistryAuthenticationPlan

    private data class GitHubOidcAuthenticationPlan(
        override val host: String,
        val username: String,
        val audience: String
    ) : RegistryAuthenticationPlan
}

/**
 * One `<authentication>` item in Maven configuration.
 *
 * Plexus uses the collection item element name to locate this class, so the class name intentionally matches the XML
 * element. Keep the full-list binding test when changing this model.
 */
class Authentication {
    var host: String? = null
    var basic: BasicAuthenticationConfiguration? = null
    var githubOidc: GitHubOidcAuthenticationConfiguration? = null
}

class BasicAuthenticationConfiguration {
    var username: String? = null
    var token: String? = null
}

class GitHubOidcAuthenticationConfiguration {
    var username: String? = null
    var audience: String? = null
}
