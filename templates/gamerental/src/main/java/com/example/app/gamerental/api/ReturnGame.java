package com.example.app.gamerental.api;

import com.example.app.authentication.RequiresRole;
import com.example.app.authentication.Role;
import com.example.app.authentication.Sender;
import com.example.app.gamerental.api.common.Game;
import com.example.app.gamerental.api.common.GameErrors;
import com.example.app.gamerental.api.common.GameId;
import io.fluxcapacitor.javaclient.modeling.AssertLegal;
import io.fluxcapacitor.javaclient.persisting.eventsourcing.Apply;

import java.util.Objects;

@RequiresRole(Role.customer)
public record ReturnGame(GameId gameId) implements GameUpdate {

    @AssertLegal
    void assertRenter(Game current, Sender user) {
        if (!Objects.equals(user, current.renter())) {
            throw GameErrors.notInPossession;
        }
    }

    @Apply
    Game apply(Game current, Sender user) {
        return current.toBuilder().stock(current.stock() + 1).renter(null).build();
    }
}
