package com.example.app.authentication

import com.example.app.user.api.CreateUser
import com.example.app.user.api.UserId
import com.example.app.user.api.model.UserDetails
import com.example.app.user.api.model.UserProfile
import io.fluxzero.idp.client.JwtClaims
import io.fluxzero.sdk.Fluxzero
import io.fluxzero.sdk.common.Message

internal object AppUsers {

    fun ensureAppUser(claims: JwtClaims): Sender {
        val userId = UserId(claims.subject())
        var userProfile = Fluxzero.loadAggregate(userId, UserProfile::class.java).get()
        if (userProfile == null) {
            Fluxzero.sendCommandAndWait<Any?>(
                Message.asMessage(
                    CreateUser(
                        userId = userId,
                        details = UserDetails(displayName(claims), claims.email()),
                        role = null
                    )
                ).addUser(Sender.system)
            )
            userProfile = Fluxzero.loadAggregate(userId, UserProfile::class.java).get()
        }
        return Sender(
            userId = userId,
            userRole = userProfile?.role
        )
    }

    private fun displayName(claims: JwtClaims): String {
        if (!claims.name().isNullOrBlank()) {
            return claims.name()
        }
        if (!claims.email().isNullOrBlank()) {
            return claims.email()
        }
        return claims.subject()
    }
}
