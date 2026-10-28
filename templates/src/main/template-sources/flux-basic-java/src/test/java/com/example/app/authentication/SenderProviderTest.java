package com.example.app.authentication;

import com.fasterxml.jackson.databind.JsonNode;
import io.fluxzero.common.ThrowingPredicate;
import io.fluxzero.common.serialization.JsonUtils;
import io.fluxzero.idp.client.FormCodec;
import io.fluxzero.idp.testsupport.localstub.FluxzeroIdpStub;
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.web.WebRequest;
import io.fluxzero.sdk.web.WebResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SenderProviderTest {
    @BeforeEach
    void cleanBefore() {
        reset();
    }

    @AfterEach
    void reset() {
        AppSessionStore.clear();
        FluxzeroIdpStub.reset();
        TestFixture.shutDownActiveFixtures();
    }

    @Test
    void localLoginCreatesApplicationSession() {
        TestFixture testFixture = TestFixture.create(
                DefaultFluxzero.builder().registerUserProvider(new SenderProvider()),
                AppAuthEndpoint.class, FluxzeroIdpStub.class);

        loginAs(testFixture, "user");
    }

    @Test
    void missingSessionReturnsUnauthenticatedObject() {
        TestFixture.create(DefaultFluxzero.builder().registerUserProvider(new SenderProvider()),
                        AppAuthEndpoint.class, FluxzeroIdpStub.class)
                .whenGetByUser(null, "/app/auth/session")
                .expectWebResult(sessionResponse(401, false, null));
    }

    private void loginAs(TestFixture testFixture, String username) {
        AtomicReference<String> authorizationUrl = new AtomicReference<>();
        AtomicReference<String> loginPath = new AtomicReference<>();
        AtomicReference<String> callbackUrl = new AtomicReference<>();
        AtomicReference<String> returnTo = new AtomicReference<>();

        testFixture
                .whenGetByUser(null, "/app/login?returnTo=/app/auth/session")
                .expectWebResult(captureRedirect(authorizationUrl, "http://localhost:8080/oauth2/auth"))

                .andThen()
                .whenGetByUser(null, authorizationUrl.get())
                .expectWebResult(captureRedirect(loginPath, "http://localhost:8080/login?"))

                .andThen()
                .whenWebRequestByUser(null, WebRequest.post(loginPath.get())
                        .contentType("application/x-www-form-urlencoded")
                        .payload(FormCodec.encode(Map.of("username", username)))
                        .build())
                .expectWebResult(captureRedirect(callbackUrl, "http://localhost:8080/app/callback"))

                .andThen()
                .whenGetByUser(null, pathAndQuery(callbackUrl.get()))
                .expectWebResult(captureRedirect(returnTo, "/app/auth/session"))

                .andThen()
                .whenGetByUser(null, returnTo.get())
                .expectWebResult(sessionResponse(200, true, username));
    }

    private static ThrowingPredicate<WebResponse> captureRedirect(
            AtomicReference<String> redirectLocation, String expectedPrefix) {
        return response -> {
            String location = response.getHeader("Location");
            redirectLocation.set(location);
            return response.getStatus() == 302 && location != null && location.startsWith(expectedPrefix);
        };
    }

    private static String pathAndQuery(String location) {
        URI uri = URI.create(location);
        if (uri.getScheme() == null) {
            return location;
        }
        return uri.getRawQuery() == null ? uri.getRawPath() : uri.getRawPath() + "?" + uri.getRawQuery();
    }

    private static ThrowingPredicate<WebResponse> sessionResponse(int status, boolean authenticated, String subject) {
        return response -> {
            assertEquals(status, response.getStatus());
            assertEquals("application/json", response.getContentType());
            assertEquals("no-store", response.getHeader("Cache-Control"));
            Map<?, ?> payload = assertInstanceOf(Map.class, response.getPayload());
            assertEquals(authenticated, payload.get("authenticated"));
            assertEquals(subject, payload.get("subject"));

            // Parse the SDK's HTTP body bytes once, just like response.json() in the browser.
            JsonNode json = JsonUtils.readTree(response.serialize(new JacksonSerializer()).getData().getValue());
            assertTrue(json.isObject(), "The HTTP body must be a JSON object, not a JSON-encoded string");
            assertTrue(json.path("authenticated").isBoolean());
            assertEquals(authenticated, json.path("authenticated").booleanValue());
            if (subject == null) {
                assertFalse(json.has("subject"));
            } else {
                assertEquals(subject, json.path("subject").textValue());
            }
            return true;
        };
    }

}
