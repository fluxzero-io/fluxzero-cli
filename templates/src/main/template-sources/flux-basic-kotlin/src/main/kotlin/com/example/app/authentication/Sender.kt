package com.example.app.authentication

import com.example.app.user.api.UserId
import com.fasterxml.jackson.annotation.JsonIgnore
import io.fluxzero.sdk.tracking.handling.authentication.User

data class Sender(
    val userId: UserId,
    val userRole: Role?
) : User {

    companion object {
        @JvmStatic
        val system = Sender(
            userId = UserId("system"),
            userRole = Role.ADMIN
        )

        @JvmStatic
        fun getCurrent(): Sender {
            return User.getCurrent()
        }
    }

    @JsonIgnore
    override fun getName(): String {
        return userId.functionalId
    }

    fun hasRole(role: Role): Boolean {
        return role.matches(userRole)
    }

    override fun hasRole(role: String): Boolean {
        return hasRole(Role.valueOf(role))
    }

    fun isAdmin(): Boolean {
        return Role.ADMIN.matches(userRole)
    }

    fun isAuthorizedFor(userId: UserId?): Boolean {
        return isAdmin() || this.userId == userId
    }
}
