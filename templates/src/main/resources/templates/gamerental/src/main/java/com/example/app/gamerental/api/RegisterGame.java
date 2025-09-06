package com.example.app.gamerental.api;

import com.example.app.authentication.RequiresRole;
import com.example.app.authentication.Role;
import com.example.app.gamerental.api.common.Game;
import com.example.app.gamerental.api.common.GameDetails;
import com.example.app.gamerental.api.common.GameErrors;
import com.example.app.gamerental.api.common.GameId;
import io.fluxcapacitor.javaclient.FluxCapacitor;
import io.fluxcapacitor.javaclient.persisting.eventsourcing.Apply;
import io.fluxcapacitor.javaclient.tracking.handling.Request;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import javax.annotation.Nullable;

@RequiresRole(Role.manager)
public record RegisterGame(GameId gameId, @NotNull @Valid GameDetails details) implements GameUpdate, Request<GameId> {

    public RegisterGame(@NotNull @Valid GameDetails details) {
        this(FluxCapacitor.generateId(GameId.class), details);
    }

    @Override
    public GameId handle() {
        GameUpdate.super.handle();
        return gameId;
    }

    @Override
    public void assertExistence(@Nullable Game current) {
        if (current != null) {
            throw GameErrors.alreadyExists;
        }
    }

    @Apply
    Game apply() {
        return Game.builder().gameId(gameId).details(details).stock(1).build();
    }
}
