package com.example.app.authentication

import io.fluxzero.common.ThrowingPredicate
import io.fluxzero.common.serialization.JsonUtils
import io.fluxzero.idp.client.FormCodec
import io.fluxzero.idp.testsupport.localstub.FluxzeroIdpStub
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer
import io.fluxzero.sdk.configuration.DefaultFluxzero
import io.fluxzero.sdk.test.TestFixture
import io.fluxzero.sdk.web.WebRequest
import io.fluxzero.sdk.web.WebResponse
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.URI
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

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
            DefaultFluxzero.builder().registerUserProvider(SenderProvider()),
            AppAuthEndpoint::class.java,
            FluxzeroIdpStub::class.java
        )

        loginAs(testFixture, "user")
    }

    @Test
    fun missingSessionReturnsUnauthenticatedObject() {
        TestFixture.create(
            DefaultFluxzero.builder().registerUserProvider(SenderProvider()),
            AppAuthEndpoint::class.java,
            FluxzeroIdpStub::class.java
        )
            .whenGetByUser(null, "/app/auth/session")
            .expectWebResult(sessionResponse(401, false, null))
    }

    private fun loginAs(testFixture: TestFixture, username: String) {
        val authorizationUrl = AtomicReference<String>()
        val loginPath = AtomicReference<String>()
        val callbackUrl = AtomicReference<String>()
        val returnTo = AtomicReference<String>()

        testFixture
            .whenGetByUser(null, "/app/login?returnTo=/app/auth/session")
            .expectWebResult(captureRedirect(authorizationUrl, "http://localhost:8080/oauth2/auth"))

            .andThen()
            .whenGetByUser(null, authorizationUrl.get())
            .expectWebResult(captureRedirect(loginPath, "http://localhost:8080/login?"))

            .andThen()
            .whenWebRequestByUser(
                null,
                WebRequest.post(loginPath.get())
                    .contentType("application/x-www-form-urlencoded")
                    .payload(FormCodec.encode(mapOf("username" to username)))
                    .build()
            )
            .expectWebResult(captureRedirect(callbackUrl, "http://localhost:8080/app/callback"))

            .andThen()
            .whenGetByUser(null, pathAndQuery(callbackUrl.get()))
            .expectWebResult(captureRedirect(returnTo, "/app/auth/session"))

            .andThen()
            .whenGetByUser(null, returnTo.get())
            .expectWebResult(sessionResponse(200, true, username))
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

    private fun sessionResponse(status: Int, authenticated: Boolean, subject: String?): ThrowingPredicate<WebResponse> {
        return ThrowingPredicate { response ->
            assertEquals(status, response.status)
            assertEquals("application/json", response.contentType)
            assertEquals("no-store", response.getHeader("Cache-Control"))
            val payload = assertIs<Map<*, *>>(response.getPayload<Any>())
            assertEquals(authenticated, payload["authenticated"])
            assertEquals(subject, payload["subject"])

            // Parse the SDK's HTTP body bytes once, just like response.json() in the browser.
            val json = JsonUtils.readTree(response.serialize(JacksonSerializer()).data.value)
            assertTrue(json.isObject, "The HTTP body must be a JSON object, not a JSON-encoded string")
            assertTrue(json.path("authenticated").isBoolean)
            assertEquals(authenticated, json.path("authenticated").booleanValue())
            if (subject == null) {
                assertFalse(json.has("subject"))
            } else {
                assertEquals(subject, json.path("subject").textValue())
            }
            true
        }
    }

}
