package com.example.app.user.api

import com.example.app.authentication.RequiresRole
import com.example.app.authentication.Role
import com.example.app.user.api.model.UserDetails
import com.example.app.user.api.model.UserProfile
import io.fluxzero.sdk.persisting.eventsourcing.Apply
import jakarta.validation.Valid
import jakarta.validation.constraints.NotNull

@RequiresRole(Role.ADMIN)
data class CreateUser(
    @field:NotNull override val userId: UserId,
    @field:NotNull @field:Valid val details: UserDetails,
    val role: Role?
) : UserUpdate {
    @Apply
    fun apply(): UserProfile {
        return UserProfile.builder()
            .userId(userId)
            .details(details)
            .role(role)
            .build()
    }
}
