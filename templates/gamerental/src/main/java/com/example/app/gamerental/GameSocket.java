package com.example.app.gamerental;

import com.example.app.gamerental.api.GetGameCatalog;
import com.example.app.gamerental.api.RegisterGame;
import com.example.app.gamerental.api.common.Game;
import io.fluxcapacitor.javaclient.FluxCapacitor;
import io.fluxcapacitor.javaclient.tracking.handling.HandleNotification;
import io.fluxcapacitor.javaclient.web.HandleSocketOpen;
import io.fluxcapacitor.javaclient.web.Path;
import io.fluxcapacitor.javaclient.web.SocketEndpoint;
import io.fluxcapacitor.javaclient.web.SocketSession;

import java.util.List;

@SocketEndpoint
@Path("/games")
public record GameSocket(SocketSession session) {
    @HandleSocketOpen
    static GameSocket onOpen(SocketSession session) {
        session.sendMessage(FluxCapacitor.queryAndWait(new GetGameCatalog()));
        return new GameSocket(session);
    }

    @HandleNotification(allowedClasses = RegisterGame.class)
    void on(Game game) {
        session.sendMessage(List.of(game));
    }
}
