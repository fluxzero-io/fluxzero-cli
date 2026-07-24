package com.example.app.authentication;

import io.fluxzero.idp.client.OidcClient;
import io.fluxzero.idp.client.OidcClientCredentials;
import io.fluxzero.idp.client.OidcLoginStateCodec;
import io.fluxzero.idp.client.OidcTenantConfig;
import io.fluxzero.idp.client.OidcTokenEndpointAuthMethod;
import io.fluxzero.sdk.configuration.ApplicationProperties;

/**
 * Resolves the OIDC tenant settings used by this generated application.
 * <p>
 * Values are resolved through Fluxzero {@link ApplicationProperties}: environment variables,
 * system properties, environment-specific property files and {@code application.properties}. The
 * same {@code fluxzero.auth.*} keys are used for production and local development. Production
 * deployments should configure the tenant explicitly; local tests receive defaults from the
 * Fluxzero IDP test-support dependency on the test classpath.
 */
final class AppAuthProperties {
    private AppAuthProperties() {
    }

    static OidcTenantConfig oidcTenantConfig() {
        String externalBaseUrl = externalBaseUrl();
        return new OidcTenantConfig(
                oidcIssuer(),
                property("fluxzero.auth.oidc.client-id"),
                property("fluxzero.auth.oidc.redirect-uri", externalBaseUrl + "/app/callback"),
                property("fluxzero.auth.oidc.resource-audience", externalBaseUrl + "/api"),
                property("fluxzero.auth.oidc.scope", "openid profile email"),
                clientCredentials());
    }

    static OidcClient oidcClient() {
        return new OidcClient(oidcTenantConfig());
    }

    static OidcLoginStateCodec loginStateCodec() {
        return new OidcLoginStateCodec(property("fluxzero.auth.oidc.login-state-secret"));
    }

    static String oidcIssuer() {
        return property("fluxzero.auth.oidc.issuer");
    }

    static String externalBaseUrl() {
        return property("fluxzero.auth.external-base-url").replaceAll("/+$", "");
    }

    static String postLogoutRedirectUri() {
        return property("fluxzero.auth.oidc.post-logout-redirect-uri", externalBaseUrl() + "/");
    }

    private static OidcClientCredentials clientCredentials() {
        OidcTokenEndpointAuthMethod authMethod = OidcTokenEndpointAuthMethod.from(
                property("fluxzero.auth.oidc.token-endpoint-auth-method", "none"));
        if (authMethod == OidcTokenEndpointAuthMethod.PRIVATE_KEY_JWT) {
            return OidcClientCredentials.privateKeyJwt(
                    property("fluxzero.auth.oidc.client-private-jwk"),
                    property("fluxzero.auth.oidc.token-endpoint-audience", ""));
        }
        return OidcClientCredentials.none();
    }

    private static String property(String name, String defaultValue) {
        return ApplicationProperties.getProperty(name, defaultValue);
    }

    private static String property(String name) {
        return ApplicationProperties.requireProperty(name);
    }
}
