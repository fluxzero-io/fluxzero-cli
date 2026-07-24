package com.example.app.authentication;

import io.fluxzero.common.serialization.JsonUtils;
import io.fluxzero.idp.client.JwtClaims;
import io.fluxzero.idp.client.OidcClient;
import io.fluxzero.idp.client.OidcLoginState;
import io.fluxzero.idp.client.OidcTenantConfig;
import io.fluxzero.idp.client.TokenValidationRequest;
import io.fluxzero.idp.client.TokenValidators;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.tracking.handling.authentication.NoUserRequired;
import io.fluxzero.sdk.web.HandleGet;
import io.fluxzero.sdk.web.Path;
import io.fluxzero.sdk.web.QueryParam;
import io.fluxzero.sdk.web.WebRequest;
import io.fluxzero.sdk.web.WebResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * BFF authentication endpoints for the generated application.
 * <p>
 * The browser is redirected to an OIDC tenant for login, returns to {@code /app/callback}, and then
 * receives an opaque application session cookie. The frontend never needs to read an ID token or
 * access token. Local development uses the same endpoints; the only intended difference is that the
 * tenant and token validator come from the local stub on the test classpath.
 */
@Component
@Path("/app")
@NoUserRequired
@Slf4j
public class AppAuthEndpoint {
    private static final String LOGIN_STATE_COOKIE = "flux_app_oidc_login";
    private static final Duration LOGIN_TTL = Duration.ofMinutes(10);

    @HandleGet("/login")
    WebResponse login(@QueryParam("returnTo") String returnTo) {
        OidcLoginState loginState = OidcLoginState.create(safeAppRedirect(returnTo), LOGIN_TTL, Fluxzero.currentTime());
        OidcClient client = AppAuthProperties.oidcClient();
        return redirectResponse(
                client.authorizationUrl(loginState),
                AppSessionStore.cookieValue(
                        LOGIN_STATE_COOKIE, AppAuthProperties.loginStateCodec().encode(loginState), LOGIN_TTL, "/app"));
    }

    @HandleGet("/callback")
    WebResponse callback(WebRequest request,
                         @QueryParam("code") String code,
                         @QueryParam("state") String state,
                         @QueryParam("error") String error) {
        if (error != null && !error.isBlank()) {
            return redirectResponse("/?login_error=1", clearLoginCookie());
        }
        if (code == null || code.isBlank() || state == null || state.isBlank()) {
            return redirectResponse("/?login_error=1", clearLoginCookie());
        }
        return request.getCookie(LOGIN_STATE_COOKIE)
                .flatMap(cookie -> AppAuthProperties.loginStateCodec().decode(cookie.getValue(), Fluxzero.currentTime()))
                .filter(loginState -> loginState.matchesState(state))
                .map(loginState -> createSession(code, loginState.codeVerifier(), loginState.redirectTo()))
                .orElseGet(() -> redirectResponse("/?login_error=1", clearLoginCookie()));
    }

    @HandleGet("/logout")
    WebResponse logout(WebRequest request) {
        String idToken = AppSessionStore.session(request)
                .map(AppSessionStore.AppSession::idToken)
                .orElse("");
        AppSessionStore.remove(request);
        String location = AppAuthProperties.oidcClient()
                .endSessionUrl(AppAuthProperties.postLogoutRedirectUri(), idToken);
        return redirectResponse(location, AppSessionStore.clearSessionCookie());
    }

    @HandleGet("/auth/session")
    WebResponse session(WebRequest request) {
        return AppSessionStore.session(request)
                .map(session -> jsonResponse(200, sessionPayload(session)))
                .orElseGet(() -> jsonResponse(401, Map.of(
                        "authenticated", false,
                        "issuer", AppAuthProperties.oidcIssuer())));
    }

    private static WebResponse createSession(String code, String verifier, String redirectTo) {
        OidcTenantConfig config = AppAuthProperties.oidcTenantConfig();
        try {
            OidcClient.TokenResponse tokenResponse = AppAuthProperties.oidcClient().exchangeCode(code, verifier);
            JwtClaims claims = TokenValidators.validate(TokenValidationRequest.idToken(tokenResponse.idToken(), config));
            Sender sender = AppUsers.ensureAppUser(claims);
            AppSessionStore.AppSession session = AppSessionStore.createSession(
                    tokenResponse.idToken(), tokenResponse.accessToken(), claims.json(), sender);
            return redirectResponse(redirectTo,
                    AppSessionStore.sessionCookie(session),
                    clearLoginCookie());
        } catch (RuntimeException e) {
            log.warn("OIDC callback failed: {}", e.getMessage());
            return redirectResponse("/?login_error=1", clearLoginCookie());
        }
    }

    private static Map<String, Object> sessionPayload(AppSessionStore.AppSession session) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("authenticated", true);
        payload.put("issuer", AppAuthProperties.oidcIssuer());
        payload.put("tenantId", session.claims().path("tenant_id").asText(""));
        payload.put("subject", session.sender().userId().getFunctionalId());
        payload.put("role", session.sender().userRole());
        payload.put("name", session.claims().path("name").asText(session.sender().getName()));
        payload.put("email", session.claims().path("email").asText(""));
        return payload;
    }

    private static String clearLoginCookie() {
        return AppSessionStore.clearCookie(LOGIN_STATE_COOKIE, "/app");
    }

    private static WebResponse redirectResponse(String redirectUrl, String... setCookies) {
        WebResponse.Builder builder = WebResponse.builder()
                .status(302)
                .payload("Redirecting...")
                .contentType("text/plain")
                .header("Location", redirectUrl)
                .header("Cache-Control", "no-store");
        for (String cookie : setCookies) {
            builder.header("Set-Cookie", cookie);
        }
        return builder.build();
    }

    static WebResponse jsonResponse(int status, Object payload) {
        return WebResponse.builder()
                .status(status)
                .payload(JsonUtils.asJson(payload))
                .contentType("application/json")
                .header("Cache-Control", "no-store")
                .build();
    }

    private static String safeAppRedirect(String returnTo) {
        if (returnTo == null || returnTo.isBlank()) {
            return "/";
        }
        if (returnTo.startsWith("/") && !returnTo.startsWith("//")) {
            return returnTo;
        }
        return "/";
    }
}
