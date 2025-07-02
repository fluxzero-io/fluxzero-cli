package com.example.app.user;

import com.example.app.authentication.Role;
import io.fluxcapacitor.javaclient.modeling.Aggregate;
import lombok.Builder;

@Aggregate(searchable = true)
@Builder(toBuilder = true)
public record UserProfile(UserId userId, UserDetails details, Role role) {
}
