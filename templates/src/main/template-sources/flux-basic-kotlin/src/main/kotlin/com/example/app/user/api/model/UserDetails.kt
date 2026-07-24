package com.example.app.user.api.model

import jakarta.validation.constraints.NotBlank

data class UserDetails(
    @field:NotBlank val name: String,
    val email: String?
)
