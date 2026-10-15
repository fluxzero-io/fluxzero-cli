package com.example.app.authentication;

import com.example.app.user.api.SetRole;
import io.fluxzero.common.ThrowingPredicate;
import io.fluxzero.common.serialization.JsonUtils;
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import com.example.app.user.api.UserId;
import com.example.app.user.api.model.UserProfile;
import io.fluxzero.idp.bff.OidcIntegration;
import io.fluxzero.idp.bff.FluxzeroOidcUser;
import io.fluxzero.idp.bff.OidcUser;
import io.fluxzero.idp.bff.OidcUserProvider;
import io.fluxzero.idp.client.FormCodec;
import io.fluxzero.idp.client.OidcApplicationRoutes;
import io.fluxzero.idp.testsupport.localstub.FluxzeroIdpStub;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.client.Client;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.handling.LocalHandler;
import io.fluxzero.sdk.web.WebRequest;
import io.fluxzero.sdk.web.WebResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class SenderProviderTest {
    @AfterEach void reset() {
        FluxzeroIdpStub.reset();
        TestFixture.shutDownActiveFixtures();
    }

    @Test void serviceLoadedMapperUsesBffSessionsAndCurrentApplicationRoles() {
        var provider = OidcUserProvider.fromServiceLoader();
        var fixture = TestFixture.createAsync(new DefaultFluxzero.Builder() {
            @Override public Fluxzero build(Client client) {
                // Keep production authentication: no TestFixture system-user fallback for anonymous requests.
                registerUserProvider(provider);
                return super.build(client);
            }
        }, new AppAuthEndpoint(), new LocalIdp());
        try (var oidc = OidcIntegration.register(fixture.getFluxzero())) {
            var routes = fixture.getFluxzero().apply(f -> OidcApplicationRoutes.fromCurrentApplication());
            var response = new AtomicReference<WebResponse>();
            var loggedIn = fixture.whenWebRequest(get(routes.sessionPath())).expectWebResult(sessionResponse(401))
                    .andThen().whenWebRequest(get("/api/me")).expectWebResult(r -> r.getStatus() == 401)
                    .andThen().whenWebRequest(get(routes.loginPath() + "?returnTo=/api/me"))
                    .expectWebResult(r -> { response.set(r); return r.getStatus() == 302; })
                    .andThen().whenWebRequest(get(response.get().getHeader("Location")))
                    .expectWebResult(r -> { response.set(r); return r.getStatus() == 302; })
                    .andThen().whenWebRequest(WebRequest.post(response.get().getHeader("Location"))
                            .header("Host", "localhost:8080").contentType("application/x-www-form-urlencoded")
                            .payload(FormCodec.encode(Map.of("username", "user"))).build())
                    .expectWebResult(r -> { response.set(r); return r.getStatus() == 302; })
                    .andThen().whenWebRequest(get(response.get().getHeader("Location")))
                    .expectWebResult(r -> r.getStatus() == 302 && "/api/me".equals(r.getHeader("Location")))
                    .andThen().whenWebRequest(get(routes.sessionPath())).expectWebResult(sessionResponse(200))
                    .andThen().whenWebRequest(get("/api/me"))
                    .expectWebResult(r -> { response.set(r); return r.getStatus() == 200; }).andThen();
            UserProfile profile = response.get().getPayloadAs(UserProfile.class);
            UserId userId = profile.userId();
            assertNull(profile.role(), "An IDP identity must not grant application roles");
            loggedIn.givenAppliedEvents(userId, new SetRole(userId, Role.ADMIN))
                    .whenWebRequest(get("/api/me"))
                    .expectWebResult(r -> r.<UserProfile>getPayloadAs(UserProfile.class).role() == Role.ADMIN)
                    .andThen().givenAppliedEvents(userId, new SetRole(userId, null))
                    .whenWebRequest(get("/api/me"))
                    .expectWebResult(r -> r.<UserProfile>getPayloadAs(UserProfile.class).role() == null)
                    .andThen().whenWebRequest(get("/api/me").toBuilder().header("Authorization", "Bearer invalid").build())
                    .expectWebResult(r -> r.getStatus() == 401);
        }
    }

    @Test void optionalProfileClaimsFallBackToEmailOrSubject() {
        var fixture = TestFixture.create();
        fixture.getFluxzero().apply(f -> {
            var mapper = new AppOidcUserMapper();
            var request = WebRequest.get("/api/me").build();
            var withoutName = mapper.map(new FluxzeroOidcUser(new OidcUser("https://issuer.example", "email-user",
                    Map.of("email", "user@example.com"))), request);
            var withoutProfile = mapper.map(new FluxzeroOidcUser(new OidcUser("https://issuer.example", "subject-user",
                    Map.of())), request);
            var emailProfile = Fluxzero.loadAggregate(withoutName.userId(), UserProfile.class).get();
            var subjectProfile = Fluxzero.loadAggregate(withoutProfile.userId(), UserProfile.class).get();
            assertEquals("user@example.com", emailProfile.details().name());
            assertEquals("subject-user", subjectProfile.details().name());
            assertNull(subjectProfile.details().email());
            assertNull(emailProfile.role());
            return null;
        });
    }

    // The stub serves HTTP payloads locally; the application still consumes asynchronous web requests.
    @LocalHandler @io.fluxzero.sdk.web.Path("/") static class LocalIdp extends FluxzeroIdpStub {}

    private static ThrowingPredicate<WebResponse> sessionResponse(int status) {
        return response -> {
            assertEquals(status, response.getStatus());
            assertEquals("application/json", response.getContentType());
            assertEquals("no-store", response.getHeader("Cache-Control"));
            assertInstanceOf(Map.class, response.getPayload());
            // Parse the HTTP body once, just like response.json() in the browser.
            var json = JsonUtils.readTree(response.serialize(new JacksonSerializer()).getData().getValue());
            assertTrue(json.isObject(), "The HTTP body must be a JSON object");
            if (status == 200) {
                assertTrue(json.path("authenticated").booleanValue());
                assertEquals("user", json.path("subject").textValue());
            } else {
                assertEquals("login_required", json.path("error").textValue());
                assertFalse(json.has("subject"));
            }
            return true;
        };
    }

    private static WebRequest get(String url) {
        var uri = java.net.URI.create(url);
        String path = uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
        return WebRequest.get(path).header("Host", "localhost:8080").build();
    }
}
