package com.example.app.user.api

import com.example.app.authentication.RequiresRole
import com.example.app.authentication.Role
import com.example.app.user.api.model.UserProfile
import io.fluxzero.sdk.persisting.eventsourcing.Apply
import jakarta.validation.constraints.NotNull

@RequiresRole(Role.ADMIN)
data class SetRole(
    @field:NotNull override val userId: UserId,
    val role: Role?
) : UserUpdate {
    @Apply
    fun apply(profile: UserProfile): UserProfile {
        return profile.toBuilder().role(role).build()
    }
}
