package com.example.app.gamerental.api.common;

import io.fluxcapacitor.common.search.Facet;
import jakarta.validation.constraints.NotBlank;

import java.time.Instant;
import java.util.List;

public record GameDetails(@NotBlank String title,
                          Instant releaseDate,
                          @NotBlank String description,
                          @Facet List<GameTag> tags) {
}
