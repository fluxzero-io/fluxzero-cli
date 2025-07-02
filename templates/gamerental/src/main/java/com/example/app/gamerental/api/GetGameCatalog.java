package com.example.app.gamerental.api;

import com.example.app.gamerental.api.common.Game;
import io.fluxcapacitor.javaclient.FluxCapacitor;
import io.fluxcapacitor.javaclient.tracking.handling.HandleQuery;
import io.fluxcapacitor.javaclient.tracking.handling.Request;

import java.util.List;

public record GetGameCatalog() implements Request<List<Game>> {
    @HandleQuery
    List<Game> handle() {
        return FluxCapacitor.search(Game.class).fetch(100);
    }
}
