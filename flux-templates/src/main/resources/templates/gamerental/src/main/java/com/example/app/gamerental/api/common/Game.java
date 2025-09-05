package com.example.app.gamerental.api.common;

import com.example.app.authentication.Sender;
import io.fluxcapacitor.common.search.Facet;
import io.fluxcapacitor.javaclient.modeling.Aggregate;
import io.fluxcapacitor.javaclient.modeling.EntityId;
import lombok.Builder;

@Aggregate(searchable = true, timestampPath = "details/releaseDate")
@Builder(toBuilder = true)
public record Game(@EntityId GameId gameId, GameDetails details, @Facet int stock, Sender renter) {
}
