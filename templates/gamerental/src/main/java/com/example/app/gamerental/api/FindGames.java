package com.example.app.gamerental.api;

import com.example.app.gamerental.api.common.Game;
import io.fluxcapacitor.javaclient.FluxCapacitor;
import io.fluxcapacitor.javaclient.tracking.handling.HandleQuery;
import io.fluxcapacitor.javaclient.tracking.handling.Request;
import jakarta.annotation.Nullable;

import java.util.List;

public record FindGames(@Nullable String term) implements Request<List<Game>> {
    @HandleQuery
    List<Game> handle() {
        return FluxCapacitor.search(Game.class).lookAhead(term).fetch(100);
    }
}
