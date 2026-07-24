package com.example.app.authentication;

import io.fluxzero.common.ThrowingPredicate;
import io.fluxzero.idp.client.FormCodec;
import io.fluxzero.idp.testsupport.localstub.FluxzeroIdpStub;
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
                DefaultFluxzero.builder().registerUserProvider(new BrowserSessionSenderProvider()),
                AppAuthEndpoint.class, FluxzeroIdpStub.class);

        loginAs(testFixture, "user");
    }

    private void loginAs(TestFixture testFixture, String username) {
        AtomicReference<String> authorizationUrl = new AtomicReference<>();
        AtomicReference<String> loginPath = new AtomicReference<>();
        AtomicReference<String> callbackUrl = new AtomicReference<>();
        AtomicReference<String> returnTo = new AtomicReference<>();

        testFixture
                .whenGet("/app/login?returnTo=/app/auth/session")
                .expectWebResult(captureRedirect(authorizationUrl, "http://localhost:8080/oauth2/auth"))

                .andThen()
                .whenGet(authorizationUrl.get())
                .expectWebResult(captureRedirect(loginPath, "/login?"))

                .andThen()
                .whenWebRequest(WebRequest.post(loginPath.get())
                        .contentType("application/x-www-form-urlencoded")
                        .payload(FormCodec.encode(Map.of("username", username)))
                        .build())
                .expectWebResult(captureRedirect(callbackUrl, "http://localhost:8080/app/callback"))

                .andThen()
                .whenGet(pathAndQuery(callbackUrl.get()))
                .expectWebResult(captureRedirect(returnTo, "/app/auth/session"))

                .andThen()
                .whenGet(returnTo.get())
                .expectWebResult(authenticatedAs(username));
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

    private static ThrowingPredicate<WebResponse> authenticatedAs(String username) {
        return response -> {
            String payload = response.toString();
            return payload.contains("\"authenticated\":true")
                    && payload.contains("\"subject\":\"%s\"".formatted(username));
        };
    }

    static class BrowserSessionSenderProvider extends SenderProvider {
        @Override
        public Sender getSystemUser() {
            return null;
        }
    }
}
