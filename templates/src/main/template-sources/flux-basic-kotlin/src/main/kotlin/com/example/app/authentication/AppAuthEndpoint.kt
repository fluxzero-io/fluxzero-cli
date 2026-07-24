package com.example.app.authentication

import io.fluxzero.common.serialization.JsonUtils
import io.fluxzero.idp.client.JwtClaims
import io.fluxzero.idp.client.OidcClient
import io.fluxzero.idp.client.OidcLoginState
import io.fluxzero.idp.client.OidcTenantConfig
import io.fluxzero.idp.client.TokenValidationRequest
import io.fluxzero.idp.client.TokenValidators
import io.fluxzero.sdk.Fluxzero
import io.fluxzero.sdk.tracking.handling.authentication.NoUserRequired
import io.fluxzero.sdk.web.HandleGet
import io.fluxzero.sdk.web.Path
import io.fluxzero.sdk.web.QueryParam
import io.fluxzero.sdk.web.WebRequest
import io.fluxzero.sdk.web.WebResponse
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * BFF authentication endpoints for the application.
 *
 * The browser is redirected to an OIDC tenant for login, returns to /app/callback, and then
 * receives an opaque application session cookie. Local development uses the same endpoints; the
 * tenant and token validator come from the local stub on the test classpath.
 */
@Component
@Path("/app")
@NoUserRequired
class AppAuthEndpoint {
    @HandleGet("/login")
    fun login(@QueryParam("returnTo") returnTo: String?): WebResponse {
        val loginState = OidcLoginState.create(safeAppRedirect(returnTo), loginTtl, Fluxzero.currentTime())
        val client = AppAuthProperties.oidcClient()
        return redirectResponse(
            client.authorizationUrl(loginState),
            AppSessionStore.cookieValue(
                loginStateCookie,
                AppAuthProperties.loginStateCodec().encode(loginState),
                loginTtl,
                "/app"
            )
        )
    }

    @HandleGet("/callback")
    fun callback(
        request: WebRequest,
        @QueryParam("code") code: String?,
        @QueryParam("state") state: String?,
        @QueryParam("error") error: String?
    ): WebResponse {
        if (!error.isNullOrBlank() || code.isNullOrBlank() || state.isNullOrBlank()) {
            return redirectResponse("/?login_error=1", clearLoginCookie())
        }
        val cookie = request.getCookie(loginStateCookie).orElse(null)
            ?: return redirectResponse("/?login_error=1", clearLoginCookie())
        val loginState = AppAuthProperties.loginStateCodec()
            .decode(cookie.value, Fluxzero.currentTime())
            .filter { it.matchesState(state) }
            .orElse(null)
            ?: return redirectResponse("/?login_error=1", clearLoginCookie())
        return createSession(code, loginState.codeVerifier(), loginState.redirectTo())
    }

    @HandleGet("/logout")
    fun logout(request: WebRequest): WebResponse {
        val idToken = AppSessionStore.session(request)?.idToken ?: ""
        AppSessionStore.remove(request)
        val location = AppAuthProperties.oidcClient()
            .endSessionUrl(AppAuthProperties.postLogoutRedirectUri(), idToken)
        return redirectResponse(location, AppSessionStore.clearSessionCookie())
    }

    @HandleGet("/auth/session")
    fun session(request: WebRequest): WebResponse {
        val session = AppSessionStore.session(request)
        return if (session == null) {
            jsonResponse(
                401,
                mapOf(
                    "authenticated" to false,
                    "issuer" to AppAuthProperties.oidcIssuer()
                )
            )
        } else {
            jsonResponse(200, sessionPayload(session))
        }
    }

    private fun createSession(code: String, verifier: String, redirectTo: String): WebResponse {
        val config: OidcTenantConfig = AppAuthProperties.oidcTenantConfig()
        return try {
            val tokenResponse: OidcClient.TokenResponse = AppAuthProperties.oidcClient().exchangeCode(code, verifier)
            val claims: JwtClaims = TokenValidators.validate(
                TokenValidationRequest.idToken(tokenResponse.idToken(), config)
            )
            val sender = AppUsers.ensureAppUser(claims)
            val session = AppSessionStore.createSession(
                tokenResponse.idToken(),
                tokenResponse.accessToken(),
                claims.json(),
                sender
            )
            redirectResponse(redirectTo, AppSessionStore.sessionCookie(session), clearLoginCookie())
        } catch (e: RuntimeException) {
            log.warn(e) { "OIDC callback failed: ${e.message}" }
            redirectResponse("/?login_error=1", clearLoginCookie())
        }
    }

    private fun sessionPayload(session: AppSessionStore.AppSession): Map<String, Any?> {
        return linkedMapOf(
            "authenticated" to true,
            "issuer" to AppAuthProperties.oidcIssuer(),
            "tenantId" to session.claims.path("tenant_id").asText(""),
            "subject" to session.sender.userId.functionalId,
            "role" to session.sender.userRole,
            "name" to session.claims.path("name").asText(session.sender.name),
            "email" to session.claims.path("email").asText("")
        )
    }

    private fun clearLoginCookie(): String {
        return AppSessionStore.clearCookie(loginStateCookie, "/app")
    }

    companion object {
        private const val loginStateCookie = "flux_app_oidc_login"
        private val loginTtl: Duration = Duration.ofMinutes(10)
        private val log = KotlinLogging.logger {}

        private fun redirectResponse(redirectUrl: String, vararg setCookies: String): WebResponse {
            val builder = WebResponse.builder()
                .status(302)
                .payload("Redirecting...")
                .contentType("text/plain")
                .header("Location", redirectUrl)
                .header("Cache-Control", "no-store")
            for (cookie in setCookies) {
                builder.header("Set-Cookie", cookie)
            }
            return builder.build()
        }

        fun jsonResponse(status: Int, payload: Any): WebResponse {
            return WebResponse.builder()
                .status(status)
                .payload(JsonUtils.asJson(payload))
                .contentType("application/json")
                .header("Cache-Control", "no-store")
                .build()
        }

        private fun safeAppRedirect(returnTo: String?): String {
            if (returnTo.isNullOrBlank()) {
                return "/"
            }
            if (returnTo.startsWith("/") && !returnTo.startsWith("//")) {
                return returnTo
            }
            return "/"
        }
    }
}
