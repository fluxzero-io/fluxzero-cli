package com.example.app.authentication

import io.fluxzero.idp.client.OidcApplicationRoutes
import io.fluxzero.idp.client.OidcTenantConfig
import io.fluxzero.sdk.Fluxzero
import io.fluxzero.sdk.configuration.ApplicationProperties

/** Settings for explicit API bearer tokens; browser authentication is owned by the BFF. */
internal object AppAuthProperties {
    fun oidcTenantConfig(): OidcTenantConfig {
        val clientId = ApplicationProperties.getProperty("fluxzero.auth.oidc.client-id", Fluxzero.get().client().applicationId())
        return OidcTenantConfig(
            ApplicationProperties.requireProperty("fluxzero.auth.oidc.issuer"), clientId,
            OidcApplicationRoutes.fromCurrentApplication().loginPath(),
            ApplicationProperties.getProperty("fluxzero.auth.oidc.resource-audience", clientId),
            ApplicationProperties.getProperty("fluxzero.auth.oidc.scope", "openid profile email")
        )
    }
}
