package com.example.app.authentication

import com.example.app.user.api.CreateUser
import com.example.app.user.api.UserId
import com.example.app.user.api.model.UserDetails
import com.example.app.user.api.model.UserProfile
import io.fluxzero.idp.client.JwtClaims
import io.fluxzero.sdk.Fluxzero
import io.fluxzero.sdk.common.Message

internal object AppUsers {

    fun ensureAppUser(claims: JwtClaims): Sender = ensureAppUser(
        claims.issuer(), claims.subject(), claims.json().path("name").asText(null),
        claims.json().path("email").asText(null)
    )

    fun ensureAppUser(issuer: String, subject: String, name: String?, email: String?): Sender {
        val userId = UserId(Fluxzero.idForName(issuer + "\n" + subject))
        var userProfile = Fluxzero.loadAggregate(userId, UserProfile::class.java).get()
        if (userProfile == null) {
            Fluxzero.sendCommandAndWait<Any?>(
                Message.asMessage(
                    CreateUser(
                        userId = userId,
                        details = UserDetails(displayName(subject, name, email), email),
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

    private fun displayName(subject: String, name: String?, email: String?): String =
        name?.takeIf { it.isNotBlank() }
            ?: email?.takeIf { it.isNotBlank() }
            ?: subject
}
