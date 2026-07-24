package com.example.app.authentication

import com.example.app.user.api.UserId
import com.example.app.user.api.model.UserProfile
import io.fluxzero.common.MessageType
import io.fluxzero.idp.client.JwtClaims
import io.fluxzero.idp.client.TokenValidationException
import io.fluxzero.idp.client.TokenValidationRequest
import io.fluxzero.idp.client.TokenValidators
import io.fluxzero.sdk.Fluxzero
import io.fluxzero.sdk.common.HasMessage
import io.fluxzero.sdk.common.serialization.DeserializingMessage
import io.fluxzero.sdk.tracking.handling.authentication.AbstractUserProvider
import io.fluxzero.sdk.tracking.handling.authentication.RefreshingUserProvider
import io.fluxzero.sdk.tracking.handling.authentication.User
import io.fluxzero.sdk.web.WebRequest

/**
 * Maps authenticated requests to the application's domain user type.
 *
 * This class deliberately lives in the application instead of the IDP client module. Fluxzero can
 * provide the OIDC protocol helpers, but deciding how a subject becomes a Sender, which aggregate is
 * loaded, and which role is assigned is domain-specific.
 */
open class SenderProvider : AbstractUserProvider(Sender::class.java), RefreshingUserProvider<Sender> {

    override fun fromMessage(message: HasMessage): User? {
        if (message is DeserializingMessage && message.messageType == MessageType.WEBREQUEST) {
            val explicitUser = super.fromMessage(message)
            if (explicitUser != null) {
                return explicitUser
            }
            val sessionSender = AppSessionStore.sender(message.metadata)
            if (sessionSender != null) {
                return refreshUser(sessionSender, message)
            }
            return bearerToken(message)
                ?.let { senderFromBearerToken(it) }
                ?.let { refreshUser(it, message) }
        }
        return super.fromMessage(message)
    }

    override fun getUserById(userId: Any): Sender {
        val userProfile = Fluxzero.loadAggregate(userId, UserProfile::class.java).get()
        if (userProfile == null) {
            //return a new unprivileged user if the user doesn't exist yet
            val uId = userId as? UserId ?: UserId(userId.toString())
            return Sender(userId = uId, userRole = null)
        }
        return Sender(userId = userProfile.userId, userRole = userProfile.role)
    }

    override fun getSystemUser(): User? {
        return Sender.system
    }

    override fun refreshUser(user: Sender?, message: HasMessage?): Sender? {
        return when (user) {
            null -> null
            else -> getUserById(user.userId)
        }
    }

    private fun bearerToken(message: DeserializingMessage): String? {
        return WebRequest.getHeader(message.metadata, "Authorization")
            .filter { it.length >= 7 && it.substring(0, 7).equals("Bearer ", ignoreCase = true) }
            .map { it.substring(7).trim() }
            .filter { it.isNotBlank() }
            .orElse(null)
    }

    private fun senderFromBearerToken(token: String): Sender? {
        return try {
            val claims: JwtClaims = TokenValidators.validate(
                TokenValidationRequest.accessToken(token, AppAuthProperties.oidcTenantConfig())
            )
            AppUsers.ensureAppUser(claims)
        } catch (_: TokenValidationException) {
            null
        }
    }
}
