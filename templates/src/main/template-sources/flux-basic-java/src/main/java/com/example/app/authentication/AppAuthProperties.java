package com.example.app.authentication;

import io.fluxzero.idp.client.OidcApplicationRoutes;
import io.fluxzero.idp.client.OidcTenantConfig;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.configuration.ApplicationProperties;

/** Settings for validating explicit API bearer tokens; browser authentication is owned by the BFF. */
final class AppAuthProperties {
    private AppAuthProperties() {}

    static OidcTenantConfig oidcTenantConfig() {
        String clientId = ApplicationProperties.getProperty("fluxzero.auth.oidc.client-id", Fluxzero.get().client().applicationId());
        return new OidcTenantConfig(ApplicationProperties.requireProperty("fluxzero.auth.oidc.issuer"), clientId,
                OidcApplicationRoutes.fromCurrentApplication().loginPath(),
                ApplicationProperties.getProperty("fluxzero.auth.oidc.resource-audience", clientId),
                ApplicationProperties.getProperty("fluxzero.auth.oidc.scope", "openid profile email"));
    }
}
