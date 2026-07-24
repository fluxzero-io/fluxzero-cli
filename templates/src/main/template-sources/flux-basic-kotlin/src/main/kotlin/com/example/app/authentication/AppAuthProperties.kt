package com.example.app.authentication

import io.fluxzero.idp.client.OidcClient
import io.fluxzero.idp.client.OidcClientCredentials
import io.fluxzero.idp.client.OidcLoginStateCodec
import io.fluxzero.idp.client.OidcTenantConfig
import io.fluxzero.idp.client.OidcTokenEndpointAuthMethod
import io.fluxzero.sdk.configuration.ApplicationProperties

/**
 * Resolves the OIDC tenant settings used by this application.
 *
 * Values are resolved through Fluxzero ApplicationProperties. The same fluxzero.auth.* keys are used
 * for production and local development. Production deployments should configure a real Fluxzero IDP
 * tenant explicitly; local tests receive defaults from the Fluxzero IDP test-support dependency.
 */
internal object AppAuthProperties {

    fun oidcTenantConfig(): OidcTenantConfig {
        val externalBaseUrl = externalBaseUrl()
        return OidcTenantConfig(
            oidcIssuer(),
            property("fluxzero.auth.oidc.client-id"),
            property("fluxzero.auth.oidc.redirect-uri", "$externalBaseUrl/app/callback"),
            property("fluxzero.auth.oidc.resource-audience", "$externalBaseUrl/api"),
            property("fluxzero.auth.oidc.scope", "openid profile email"),
            clientCredentials()
        )
    }

    fun oidcClient(): OidcClient {
        return OidcClient(oidcTenantConfig())
    }

    fun loginStateCodec(): OidcLoginStateCodec {
        return OidcLoginStateCodec(property("fluxzero.auth.oidc.login-state-secret"))
    }

    fun oidcIssuer(): String {
        return property("fluxzero.auth.oidc.issuer")
    }

    fun externalBaseUrl(): String {
        return property("fluxzero.auth.external-base-url").replace(Regex("/+$"), "")
    }

    fun postLogoutRedirectUri(): String {
        return property("fluxzero.auth.oidc.post-logout-redirect-uri", "${externalBaseUrl()}/")
    }

    private fun clientCredentials(): OidcClientCredentials {
        val authMethod = OidcTokenEndpointAuthMethod.from(
            property("fluxzero.auth.oidc.token-endpoint-auth-method", "none")
        )
        return if (authMethod == OidcTokenEndpointAuthMethod.PRIVATE_KEY_JWT) {
            OidcClientCredentials.privateKeyJwt(
                property("fluxzero.auth.oidc.client-private-jwk"),
                property("fluxzero.auth.oidc.token-endpoint-audience", "")
            )
        } else {
            OidcClientCredentials.none()
        }
    }

    private fun property(name: String, defaultValue: String): String {
        return ApplicationProperties.getProperty(name, defaultValue)
    }

    private fun property(name: String): String {
        return ApplicationProperties.requireProperty(name)
    }
}
