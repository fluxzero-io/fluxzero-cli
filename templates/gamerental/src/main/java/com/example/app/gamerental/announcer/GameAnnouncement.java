package com.example.app.gamerental.announcer;

import com.example.app.authentication.RequiresRole;
import com.example.app.authentication.Role;
import com.example.app.gamerental.api.common.GameId;
import jakarta.validation.constraints.NotNull;

@RequiresRole(Role.admin)
public record GameAnnouncement(@NotNull GameId gameId, String message) {
}
