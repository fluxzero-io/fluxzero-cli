package com.example.app.user.api.model;

import jakarta.validation.constraints.NotBlank;

public record UserDetails(@NotBlank String name, String email) {
}
