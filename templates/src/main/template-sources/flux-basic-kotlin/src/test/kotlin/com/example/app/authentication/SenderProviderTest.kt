package com.example.app.authentication

import com.example.app.user.api.SetRole
import com.example.app.user.api.model.UserProfile
import io.fluxzero.idp.bff.OidcIntegration
import io.fluxzero.idp.bff.FluxzeroOidcUser
import io.fluxzero.idp.bff.OidcUser
import io.fluxzero.idp.bff.OidcUserProvider
import io.fluxzero.idp.client.FormCodec
import io.fluxzero.idp.client.OidcApplicationRoutes
import io.fluxzero.idp.testsupport.localstub.FluxzeroIdpStub
import io.fluxzero.sdk.Fluxzero
import io.fluxzero.sdk.configuration.DefaultFluxzero
import io.fluxzero.sdk.configuration.client.Client
import io.fluxzero.sdk.test.TestFixture
import io.fluxzero.sdk.tracking.handling.LocalHandler
import io.fluxzero.sdk.web.WebRequest
import io.fluxzero.sdk.web.WebResponse
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicReference

class SenderProviderTest {
    @Test fun optionalProfileClaimsFallBackToEmailOrSubject() {
        val fixture = TestFixture.create()
        fixture.fluxzero.apply {
            val mapper = AppOidcUserMapper()
            val request = WebRequest.get("/api/me").build()
            val withoutName = mapper.map(FluxzeroOidcUser(OidcUser("https://issuer.example", "email-user",
                mapOf("email" to "user@example.com"))), request)
            val withoutProfile = mapper.map(FluxzeroOidcUser(OidcUser("https://issuer.example", "subject-user",
                emptyMap<String, Any>())), request)
            val emailProfile = Fluxzero.loadAggregate(withoutName.userId, UserProfile::class.java).get()!!
            val subjectProfile = Fluxzero.loadAggregate(withoutProfile.userId, UserProfile::class.java).get()!!
            assertEquals("user@example.com", emailProfile.details.name)
            assertEquals("subject-user", subjectProfile.details.name)
            assertNull(subjectProfile.details.email)
            assertNull(emailProfile.role)
        }
    }

    @AfterEach fun reset() {
        FluxzeroIdpStub.reset()
        TestFixture.shutDownActiveFixtures()
    }

    @Test fun serviceLoadedMapperUsesBffSessionsAndCurrentApplicationRoles() {
        val provider = OidcUserProvider.fromServiceLoader()
        var fixture = TestFixture.createAsync(object : DefaultFluxzero.Builder() {
            override fun build(client: Client): Fluxzero {
                // Keep production authentication: no TestFixture system-user fallback for anonymous requests.
                registerUserProvider(provider)
                return super.build(client)
            }
        }, AppAuthEndpoint(), LocalIdp())
        OidcIntegration.register(fixture.fluxzero).use {
            val routes = fixture.fluxzero.apply { OidcApplicationRoutes.fromCurrentApplication() }
            val response = AtomicReference<WebResponse>()
            val loggedIn = fixture.whenWebRequest(get("/api/me")).expectWebResult { it.status == 401 }
                .andThen().whenWebRequest(get(routes.loginPath() + "?returnTo=/api/me"))
                .expectWebResult { response.set(it); it.status == 302 }
                .andThen().whenWebRequest(get(response.get().getHeader("Location")))
                .expectWebResult { response.set(it); it.status == 302 }
                .andThen().whenWebRequest(WebRequest.post(response.get().getHeader("Location"))
                    .header("Host", "localhost:8080").contentType("application/x-www-form-urlencoded")
                    .payload(FormCodec.encode(mapOf("username" to "user"))).build())
                .expectWebResult { response.set(it); it.status == 302 }
                .andThen().whenWebRequest(get(response.get().getHeader("Location")))
                .expectWebResult { it.status == 302 && it.getHeader("Location") == "/api/me" }
                .andThen().whenWebRequest(get("/api/me"))
                .expectWebResult { response.set(it); it.status == 200 }.andThen()
            val profile = response.get().getPayloadAs<UserProfile>(UserProfile::class.java)
            val userId = profile.userId
            assertNull(profile.role, "An IDP identity must not grant application roles")
            loggedIn.givenAppliedEvents(userId, SetRole(userId, Role.ADMIN))
                .whenWebRequest(get("/api/me"))
                .expectWebResult { it.getPayloadAs<UserProfile>(UserProfile::class.java).role == Role.ADMIN }
                .andThen().givenAppliedEvents(userId, SetRole(userId, null))
                .whenWebRequest(get("/api/me"))
                .expectWebResult { it.getPayloadAs<UserProfile>(UserProfile::class.java).role == null }
                .andThen().whenWebRequest(get("/api/me").toBuilder().header("Authorization", "Bearer invalid").build())
                .expectWebResult { it.status == 401 }
        }
    }

    // The stub serves HTTP payloads locally; the application still consumes asynchronous web requests.
    @LocalHandler @io.fluxzero.sdk.web.Path("/") class LocalIdp : FluxzeroIdpStub()

    private fun get(url: String): WebRequest {
        val uri = java.net.URI.create(url)
        val path = uri.rawPath + (uri.rawQuery?.let { "?$it" } ?: "")
        return WebRequest.get(path).header("Host", "localhost:8080").build()
    }
}
