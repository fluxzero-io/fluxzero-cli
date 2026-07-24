package com.example.app.user.api;

import com.example.app.authentication.RequiresRole;
import com.example.app.authentication.Role;
import com.example.app.user.api.model.UserProfile;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import jakarta.validation.constraints.NotNull;

@RequiresRole(Role.ADMIN)
public record SetRole(@NotNull UserId userId, Role role) implements UserUpdate {
    @Apply
    UserProfile apply(UserProfile profile) {
        return profile.toBuilder().role(role).build();
    }
}
