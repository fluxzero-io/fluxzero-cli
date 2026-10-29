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
        return ensureAppUser(claims.issuer(), claims.subject(), claims.json().path("name").asText(null),
                claims.json().path("email").asText(null));
    }

    static Sender ensureAppUser(String issuer, String subject, String name, String email) {
        UserId userId = new UserId(Fluxzero.idForName(issuer + "\n" + subject));
        UserProfile userProfile = Fluxzero.loadAggregate(userId, UserProfile.class).get();
        if (userProfile == null) {
            Fluxzero.sendCommandAndWait(Message.asMessage(new CreateUser(
                    userId,
                    new UserDetails(displayName(subject, name, email), email),
                    null)).addUser(Sender.system));
            userProfile = Fluxzero.loadAggregate(userId, UserProfile.class).get();
        }
        return Sender.builder()
                .userId(userId)
                .userRole(userProfile == null ? null : userProfile.role())
                .build();
    }

    private static String displayName(String subject, String name, String email) {
        if (name != null && !name.isBlank()) return name;
        return email == null || email.isBlank() ? subject : email;
    }
}
