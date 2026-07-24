package com.example.app.authentication;

import com.example.app.user.api.UserId;
import com.example.app.user.api.model.UserProfile;
import io.fluxzero.common.MessageType;
import io.fluxzero.idp.client.JwtClaims;
import io.fluxzero.idp.client.TokenValidationException;
import io.fluxzero.idp.client.TokenValidationRequest;
import io.fluxzero.idp.client.TokenValidators;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.HasMessage;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.tracking.handling.authentication.AbstractUserProvider;
import io.fluxzero.sdk.tracking.handling.authentication.RefreshingUserProvider;
import io.fluxzero.sdk.tracking.handling.authentication.User;
import io.fluxzero.sdk.web.WebRequest;

import java.util.Optional;

/**
 * Maps authenticated requests to the application's domain user type.
 * <p>
 * This class deliberately lives in the generated application instead of the IDP client module.
 * Fluxzero can provide the OIDC protocol helpers, but deciding how a subject becomes a
 * {@link Sender}, which aggregate is loaded, and which role is assigned is domain-specific. Browser
 * requests normally authenticate through the BFF session cookie; bearer access tokens are still
 * accepted for API-style calls and are validated through the same {@link TokenValidators} facade.
 */
public class SenderProvider extends AbstractUserProvider implements RefreshingUserProvider<Sender> {

    public SenderProvider() {
        super(Sender.class);
    }

    @Override
    public User fromMessage(HasMessage message) {
        if (message instanceof DeserializingMessage dm && dm.getMessageType() == MessageType.WEBREQUEST) {
            User explicitUser = super.fromMessage(message);
            if (explicitUser != null) {
                return explicitUser;
            }
            Optional<Sender> sessionSender = AppSessionStore.sender(dm.getMetadata());
            if (sessionSender.isPresent()) {
                return refreshUser(sessionSender.get(), message);
            }
            return bearerToken(dm)
                    .flatMap(SenderProvider::senderFromBearerToken)
                    .map(sender -> refreshUser(sender, message))
                    .orElse(null);
        }
        return super.fromMessage(message);
    }

    @Override
    public User getUserById(Object userId) {
        UserProfile userProfile = Fluxzero.loadAggregate(userId, UserProfile.class).get();
        if (userProfile == null) {
            //return a new unprivileged user if the user doesn't exist yet
            return Sender.builder().userId(userId instanceof UserId uId ? uId : new UserId(userId.toString())).build();
        }
        return Sender.builder().userId(userProfile.userId()).userRole(userProfile.role()).build();
    }

    @Override
    public User getSystemUser() {
        return Sender.system;
    }

    @Override
    public Sender refreshUser(Sender user, HasMessage message) {
        return user == null ? null : (Sender) getUserById(user.userId());
    }

    private static Optional<String> bearerToken(DeserializingMessage message) {
        return WebRequest.getHeader(message.getMetadata(), "Authorization")
                .filter(header -> header.regionMatches(true, 0, "Bearer ", 0, 7))
                .map(header -> header.substring(7).trim())
                .filter(token -> !token.isBlank());
    }

    private static Optional<Sender> senderFromBearerToken(String token) {
        try {
            JwtClaims claims = TokenValidators.validate(TokenValidationRequest.accessToken(
                    token, AppAuthProperties.oidcTenantConfig()));
            return Optional.of(AppUsers.ensureAppUser(claims));
        } catch (TokenValidationException e) {
            return Optional.empty();
        }
    }
}
