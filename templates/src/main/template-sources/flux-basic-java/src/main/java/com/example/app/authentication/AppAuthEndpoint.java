package com.example.app.authentication;

import com.example.app.user.api.model.UserProfile;
import io.fluxzero.idp.client.OidcApplicationRoutes;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.tracking.handling.authentication.NoUserRequired;
import io.fluxzero.sdk.tracking.handling.authentication.RequiresUser;
import io.fluxzero.sdk.web.HandleGet;
import io.fluxzero.sdk.web.Path;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Application profile and public frontend configuration. Login and session endpoints belong to the library. */
@Component
@Path("/api")
public class AppAuthEndpoint {
    @HandleGet("/auth") @NoUserRequired
    Map<String, String> auth() {
        String session = OidcApplicationRoutes.fromCurrentApplication().sessionPath();
        return Map.of("clientUrl", session.substring(0, session.lastIndexOf('/') + 1) + "client.js");
    }

    @HandleGet("/me") @RequiresUser
    UserProfile me(Sender sender) {
        return Fluxzero.loadAggregate(sender.userId(), UserProfile.class).get();
    }
}
