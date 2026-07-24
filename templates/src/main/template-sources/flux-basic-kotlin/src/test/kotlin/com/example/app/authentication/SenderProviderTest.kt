package com.example.app.authentication

import io.fluxzero.common.ThrowingPredicate
import io.fluxzero.idp.client.FormCodec
import io.fluxzero.idp.testsupport.localstub.FluxzeroIdpStub
import io.fluxzero.sdk.configuration.DefaultFluxzero
import io.fluxzero.sdk.test.TestFixture
import io.fluxzero.sdk.tracking.handling.authentication.User
import io.fluxzero.sdk.web.WebRequest
import io.fluxzero.sdk.web.WebResponse
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.URI
import java.util.concurrent.atomic.AtomicReference

class SenderProviderTest {

    @BeforeEach
    fun cleanBefore() {
        reset()
    }

    @AfterEach
    fun reset() {
        AppSessionStore.clear()
        FluxzeroIdpStub.reset()
        TestFixture.shutDownActiveFixtures()
    }

    @Test
    fun localLoginCreatesApplicationSession() {
        val testFixture = TestFixture.create(
            DefaultFluxzero.builder().registerUserProvider(BrowserSessionSenderProvider()),
            AppAuthEndpoint::class.java,
            FluxzeroIdpStub::class.java
        )

        loginAs(testFixture, "user")
    }

    private fun loginAs(testFixture: TestFixture, username: String) {
        val authorizationUrl = AtomicReference<String>()
        val loginPath = AtomicReference<String>()
        val callbackUrl = AtomicReference<String>()
        val returnTo = AtomicReference<String>()

        testFixture
            .whenGet("/app/login?returnTo=/app/auth/session")
            .expectWebResult(captureRedirect(authorizationUrl, "http://localhost:8080/oauth2/auth"))

            .andThen()
            .whenGet(authorizationUrl.get())
            .expectWebResult(captureRedirect(loginPath, "/login?"))

            .andThen()
            .whenWebRequest(
                WebRequest.post(loginPath.get())
                    .contentType("application/x-www-form-urlencoded")
                    .payload(FormCodec.encode(mapOf("username" to username)))
                    .build()
            )
            .expectWebResult(captureRedirect(callbackUrl, "http://localhost:8080/app/callback"))

            .andThen()
            .whenGet(pathAndQuery(callbackUrl.get()))
            .expectWebResult(captureRedirect(returnTo, "/app/auth/session"))

            .andThen()
            .whenGet(returnTo.get())
            .expectWebResult(authenticatedAs(username))
    }

    private fun captureRedirect(
        redirectLocation: AtomicReference<String>,
        expectedPrefix: String
    ): ThrowingPredicate<WebResponse> {
        return ThrowingPredicate { response ->
            val location = response.getHeader("Location")
            redirectLocation.set(location)
            response.status == 302 && location != null && location.startsWith(expectedPrefix)
        }
    }

    private fun pathAndQuery(location: String): String {
        val uri = URI.create(location)
        if (uri.scheme == null) {
            return location
        }
        return if (uri.rawQuery == null) uri.rawPath else "${uri.rawPath}?${uri.rawQuery}"
    }

    private fun authenticatedAs(username: String): ThrowingPredicate<WebResponse> {
        return ThrowingPredicate { response ->
            val payload = response.toString()
            payload.contains("\"authenticated\":true") &&
                payload.contains("\"subject\":\"$username\"")
        }
    }

    private class BrowserSessionSenderProvider : SenderProvider() {
        override fun getSystemUser(): User? {
            return null
        }
    }
}
