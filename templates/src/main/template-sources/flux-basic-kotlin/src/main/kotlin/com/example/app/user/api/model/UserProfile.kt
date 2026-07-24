package com.example.app.user.api.model

import com.example.app.authentication.Role
import com.example.app.authentication.Sender
import com.example.app.user.api.UserId
import io.fluxzero.sdk.common.serialization.FilterContent
import io.fluxzero.sdk.modeling.Aggregate
import io.fluxzero.sdk.modeling.EventPublication

@Aggregate(searchable = true, eventPublication = EventPublication.IF_MODIFIED)
data class UserProfile(
    val userId: UserId,
    val details: UserDetails,
    val role: Role?
) {
    companion object {
        @JvmStatic
        fun builder(): UserProfileBuilder = UserProfileBuilder()
    }

    fun toBuilder(): UserProfileBuilder = UserProfileBuilder()
        .userId(userId)
        .details(details)
        .role(role)

    @FilterContent
    fun filter(sender: Sender): UserProfile? {
        return if (sender.isAuthorizedFor(userId)) this else null
    }
}

class UserProfileBuilder {
    private var userId: UserId? = null
    private var details: UserDetails? = null
    private var role: Role? = null

    fun userId(userId: UserId): UserProfileBuilder {
        this.userId = userId
        return this
    }

    fun details(details: UserDetails): UserProfileBuilder {
        this.details = details
        return this
    }

    fun role(role: Role?): UserProfileBuilder {
        this.role = role
        return this
    }

    fun build(): UserProfile {
        return UserProfile(
            userId = userId ?: throw IllegalStateException("userId is required"),
            details = details ?: throw IllegalStateException("details is required"),
            role = role
        )
    }
}
