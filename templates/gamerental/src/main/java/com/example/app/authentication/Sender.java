package com.example.app.authentication;

import com.example.app.user.UserId;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.fluxcapacitor.javaclient.tracking.handling.authentication.User;
import lombok.Builder;
import lombok.NonNull;

@Builder(toBuilder = true)
public record Sender(@NonNull UserId userId, Role userRole) implements User {

    public static final Sender system = builder()
            .userId(new UserId("system")).userRole(Role.admin).build();

    public static Sender getCurrent() {
        return User.getCurrent();
    }

    @Override
    @JsonIgnore
    public String getName() {
        return userId.getFunctionalId();
    }

    public boolean hasRole(Role role) {
        return role.matches(userRole);
    }

    @Override
    public boolean hasRole(String role) {
        return hasRole(Role.valueOf(role));
    }

    public boolean isAdmin() {
        return Role.admin.matches(userRole);
    }
}
