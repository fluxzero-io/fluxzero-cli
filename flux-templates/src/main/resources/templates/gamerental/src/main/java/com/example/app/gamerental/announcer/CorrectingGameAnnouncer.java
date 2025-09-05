package com.example.app.gamerental.announcer;

import com.example.app.gamerental.api.RegisterGame;
import io.fluxcapacitor.javaclient.FluxCapacitor;
import io.fluxcapacitor.javaclient.tracking.handling.HandleError;
import io.fluxcapacitor.javaclient.tracking.handling.Trigger;
import org.springframework.stereotype.Component;

@Component
public class CorrectingGameAnnouncer {

    @HandleError
    void on(@Trigger(consumer = "misterFlaky") RegisterGame event) {
        FluxCapacitor.publishEvent(new GameAnnouncement(event.gameId(), "Correct that game announcement"));
    }

}
