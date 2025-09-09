package com.example.app.user;

import io.fluxcapacitor.javaclient.FluxCapacitor;
import io.fluxcapacitor.javaclient.tracking.TrackSelf;
import io.fluxcapacitor.javaclient.tracking.handling.HandleCommand;
import jakarta.validation.constraints.NotNull;

@TrackSelf
public interface UserCommand {
    @NotNull UserId userId();

    @HandleCommand
    default void handle() {
        FluxCapacitor.loadAggregate(userId()).assertAndApply(this);
    }
}
