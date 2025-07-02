package com.example.app.user;

import jakarta.validation.constraints.NotBlank;

public record UserDetails(@NotBlank String name) {
}
