package com.example.app.user;

import io.fluxcapacitor.javaclient.FluxCapacitor;
import io.fluxcapacitor.javaclient.tracking.handling.HandleQuery;
import io.fluxcapacitor.javaclient.tracking.handling.Request;

import java.util.List;

public record GetUsers() implements Request<List<UserProfile>> {
    @HandleQuery
    List<UserProfile> handle() {
        return FluxCapacitor.search(UserProfile.class).fetchAll();
    }
}
