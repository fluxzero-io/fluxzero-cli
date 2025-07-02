package com.example.app.user;

import io.fluxcapacitor.javaclient.FluxCapacitor;
import io.fluxcapacitor.javaclient.modeling.Id;

public class UserId extends Id<UserProfile> {
    public UserId() {
        this(FluxCapacitor.generateId());
    }

    public UserId(String id) {
        super(id);
    }
}
