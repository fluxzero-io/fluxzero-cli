package com.example.app.authentication

import com.fasterxml.jackson.databind.JsonNode
import io.fluxzero.common.api.Metadata
import io.fluxzero.sdk.Fluxzero
import io.fluxzero.sdk.web.WebRequest
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.HexFormat
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory BFF session store for the example application.
 *
 * The browser receives only opaque HTTP-only cookies. ID/access tokens and mapped sender data stay
 * on the backend side so frontend code does not need to handle bearer tokens.
 */
object AppSessionStore {
    const val SESSION_COOKIE = "flux_app_session"

    private val sessionTtl = Duration.ofHours(8)
    private val random = SecureRandom()
    private val sessions = ConcurrentHashMap<String, AppSession>()

    internal fun createSession(
        idToken: String,
        accessToken: String,
        claims: JsonNode,
        sender: Sender
    ): AppSession {
        prune()
        val sessionId = randomToken()
        val session = AppSession(
            sessionId = sessionId,
            idToken = idToken,
            accessToken = accessToken,
            claims = claims,
            sender = sender,
            expiresAt = Fluxzero.currentTime().plus(sessionTtl)
        )
        sessions[sessionId] = session
        return session
    }

    internal fun session(request: WebRequest): AppSession? {
        prune()
        return WebRequest.getCookie(request.metadata, SESSION_COOKIE)
            .map { it.value }
            .flatMap { session(it) }
            .orElse(null)
    }

    fun sender(metadata: Metadata): Sender? {
        prune()
        return WebRequest.getCookie(metadata, SESSION_COOKIE)
            .map { it.value }
            .flatMap { session(it) }
            .map { it.sender }
            .orElse(null)
    }

    internal fun remove(request: WebRequest) {
        WebRequest.getCookie(request.metadata, SESSION_COOKIE)
            .map { it.value }
            .ifPresent { sessions.remove(it) }
    }

    internal fun cookieValue(name: String, value: String, maxAge: Duration, path: String): String {
        return "%s=%s; Path=%s; Max-Age=%d; HttpOnly; SameSite=Lax%s".format(
            name,
            URLEncoder.encode(value, StandardCharsets.UTF_8),
            path,
            maxAge.seconds,
            secureCookieSuffix()
        )
    }

    internal fun clearCookie(name: String, path: String): String {
        return "%s=; Path=%s; Max-Age=0; HttpOnly; SameSite=Lax%s".format(name, path, secureCookieSuffix())
    }

    internal fun sessionCookie(session: AppSession): String {
        return cookieValue(SESSION_COOKIE, session.sessionId, sessionTtl, "/")
    }

    internal fun clearSessionCookie(): String {
        return clearCookie(SESSION_COOKIE, "/")
    }

    fun clear() {
        sessions.clear()
    }

    private fun session(sessionId: String): java.util.Optional<AppSession> {
        return java.util.Optional.ofNullable(sessions[sessionId])
            .filter { it.expiresAt.isAfter(Fluxzero.currentTime()) }
    }

    private fun randomToken(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return HexFormat.of().formatHex(bytes)
    }

    private fun prune() {
        val now = Fluxzero.currentTime()
        sessions.entries.removeIf { it.value.expiresAt.isBefore(now) }
    }

    private fun secureCookieSuffix(): String {
        return if (AppAuthProperties.externalBaseUrl().startsWith("https://")) "; Secure" else ""
    }

    data class AppSession(
        val sessionId: String,
        val idToken: String,
        val accessToken: String,
        val claims: JsonNode,
        val sender: Sender,
        val expiresAt: Instant
    )
}
