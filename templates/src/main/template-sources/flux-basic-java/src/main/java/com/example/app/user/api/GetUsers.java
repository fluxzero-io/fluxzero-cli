package com.example.app.user.api;

import com.example.app.user.api.model.UserProfile;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.serialization.FilterContent;
import io.fluxzero.sdk.tracking.handling.HandleQuery;
import io.fluxzero.sdk.tracking.handling.Request;

import java.util.List;

public record GetUsers() implements Request<List<UserProfile>> {
    @HandleQuery
    @FilterContent
    List<UserProfile> handle() {
        return Fluxzero.search(UserProfile.class).fetchAll();
    }
}
