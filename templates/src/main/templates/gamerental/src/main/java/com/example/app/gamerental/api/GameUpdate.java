package com.example.app.gamerental.api;

import com.example.app.gamerental.api.common.Game;
import com.example.app.gamerental.api.common.GameErrors;
import com.example.app.gamerental.api.common.GameId;
import io.fluxcapacitor.javaclient.FluxCapacitor;
import io.fluxcapacitor.javaclient.modeling.AssertLegal;
import io.fluxcapacitor.javaclient.publishing.routing.RoutingKey;
import io.fluxcapacitor.javaclient.tracking.TrackSelf;
import io.fluxcapacitor.javaclient.tracking.handling.HandleCommand;
import jakarta.validation.constraints.NotNull;

import javax.annotation.Nullable;

@TrackSelf
public interface GameUpdate {
    @NotNull
    @RoutingKey
    GameId gameId();

    @AssertLegal
    default void assertExistence(@Nullable Game current) {
        if (current == null) {
            throw GameErrors.notFound;
        }
    }

    @HandleCommand
    default Object handle() {
        FluxCapacitor.loadAggregate(gameId()).assertAndApply(this);
        return null;
    }
}
