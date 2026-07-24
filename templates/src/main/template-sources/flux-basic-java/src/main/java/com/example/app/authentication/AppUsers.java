package com.example.app.authentication;

import com.example.app.user.api.CreateUser;
import com.example.app.user.api.UserId;
import com.example.app.user.api.model.UserDetails;
import com.example.app.user.api.model.UserProfile;
import io.fluxzero.idp.client.JwtClaims;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.Message;

final class AppUsers {
    private AppUsers() {
    }

    static Sender ensureAppUser(JwtClaims claims) {
        UserId userId = new UserId(claims.subject());
        UserProfile userProfile = Fluxzero.loadAggregate(userId, UserProfile.class).get();
        if (userProfile == null) {
            Fluxzero.sendCommandAndWait(Message.asMessage(new CreateUser(
                    userId,
                    new UserDetails(displayName(claims), claims.email()),
                    null)).addUser(Sender.system));
            userProfile = Fluxzero.loadAggregate(userId, UserProfile.class).get();
        }
        return Sender.builder()
                .userId(userId)
                .userRole(userProfile == null ? null : userProfile.role())
                .build();
    }

    private static String displayName(JwtClaims claims) {
        if (claims.name() != null && !claims.name().isBlank()) {
            return claims.name();
        }
        if (claims.email() != null && !claims.email().isBlank()) {
            return claims.email();
        }
        return claims.subject();
    }
}
