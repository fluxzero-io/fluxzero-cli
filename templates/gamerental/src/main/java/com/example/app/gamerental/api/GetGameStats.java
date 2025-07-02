package com.example.app.gamerental.api;

import com.example.app.gamerental.api.common.Game;
import io.fluxcapacitor.common.api.search.FacetStats;
import io.fluxcapacitor.javaclient.FluxCapacitor;
import io.fluxcapacitor.javaclient.tracking.handling.HandleQuery;
import io.fluxcapacitor.javaclient.tracking.handling.Request;

import java.util.List;

public record GetGameStats(String name) implements Request<List<FacetStats>> {
    @HandleQuery
    List<FacetStats> handle() {
        return FluxCapacitor.search(Game.class).facetStats().stream()
                .filter(facet -> facet.getName().equals(name)).toList();
    }
}
