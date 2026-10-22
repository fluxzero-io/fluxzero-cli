package com.example.app.authentication

import com.example.app.user.api.model.UserProfile
import io.fluxzero.idp.client.OidcApplicationRoutes
import io.fluxzero.sdk.Fluxzero
import io.fluxzero.sdk.tracking.handling.authentication.NoUserRequired
import io.fluxzero.sdk.tracking.handling.authentication.RequiresUser
import io.fluxzero.sdk.web.HandleGet
import io.fluxzero.sdk.web.Path
import org.springframework.stereotype.Component

/** Application profile and public frontend configuration; the library owns login and sessions. */
@Component
@Path("/api")
class AppAuthEndpoint {
    @HandleGet("/auth") @NoUserRequired
    fun auth(): Map<String, String> {
        val session = OidcApplicationRoutes.fromCurrentApplication().sessionPath()
        return mapOf("clientUrl" to session.substringBeforeLast('/') + "/client.js")
    }

    @HandleGet("/me") @RequiresUser
    fun me(sender: Sender): UserProfile? = Fluxzero.loadAggregate(sender.userId, UserProfile::class.java).get()
}
