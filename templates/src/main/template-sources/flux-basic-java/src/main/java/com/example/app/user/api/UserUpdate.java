package com.example.app.user.api;

import com.example.app.authentication.Sender;
import com.example.app.user.api.model.UserProfile;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.modeling.AssertLegal;
import io.fluxzero.sdk.tracking.Consumer;
import io.fluxzero.sdk.tracking.TrackSelf;
import io.fluxzero.sdk.tracking.handling.HandleCommand;
import jakarta.validation.constraints.NotNull;

@TrackSelf
@Consumer(name = "user-update")
public interface UserUpdate {
    @NotNull
    UserId userId();

    @AssertLegal
    default void assertAuthorized(UserProfile user, Sender sender) {
        if (!sender.isAdmin() && !user.userId().equals(sender.userId())) {
            throw UserErrors.unauthorized;
        }
    }

    @HandleCommand
    default void handle() {
        Fluxzero.loadAggregate(userId()).assertAndApply(this);
    }
}
