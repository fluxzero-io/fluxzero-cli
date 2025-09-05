package com.example.app.gamerental;

import com.example.app.gamerental.api.FindGames;
import com.example.app.gamerental.api.GetGame;
import com.example.app.gamerental.api.GetGameStats;
import com.example.app.gamerental.api.RegisterGame;
import com.example.app.gamerental.api.RentGame;
import com.example.app.gamerental.api.common.Game;
import com.example.app.gamerental.api.common.GameDetails;
import com.example.app.gamerental.api.common.GameId;
import io.fluxcapacitor.common.api.search.FacetStats;
import io.fluxcapacitor.javaclient.FluxCapacitor;
import io.fluxcapacitor.javaclient.web.HandleGet;
import io.fluxcapacitor.javaclient.web.HandlePost;
import io.fluxcapacitor.javaclient.web.Path;
import io.fluxcapacitor.javaclient.web.PathParam;
import io.fluxcapacitor.javaclient.web.QueryParam;
import io.fluxcapacitor.javaclient.web.ServeStatic;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CompletableFuture;

@Component
@Path("/games")
@ServeStatic(value = "/static")
public class GameApi {

    @HandlePost
    CompletableFuture<GameId> registerGame(GameDetails details) {
        return FluxCapacitor.sendCommand(new RegisterGame(details));
    }

    @HandleGet("/{gameId}")
    CompletableFuture<Game> getGame(@PathParam GameId gameId) {
        return FluxCapacitor.query(new GetGame(gameId));
    }

    @HandleGet
    CompletableFuture<List<Game>> getGames(@QueryParam String term) {
        return FluxCapacitor.query(new FindGames(term));
    }

    @HandleGet("/stats")
    CompletableFuture<List<FacetStats>> getGameStats(@QueryParam String name) {
        return FluxCapacitor.query(new GetGameStats(name));
    }

    @HandlePost("/{gameId}/rent")
    CompletableFuture<Void> rentGame(@PathParam GameId gameId) {
        return FluxCapacitor.sendCommand(new RentGame(gameId));
    }

    @HandlePost("/{gameId}/return")
    CompletableFuture<Void> returnGame(@PathParam GameId gameId) {
        return FluxCapacitor.sendCommand(new RentGame(gameId));
    }

}
