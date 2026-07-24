package com.example.app.authentication;

import com.fasterxml.jackson.databind.JsonNode;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.web.WebRequest;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory BFF session store for the example application.
 * <p>
 * The browser receives only opaque HTTP-only cookies. ID/access tokens and mapped sender data stay on
 * the backend side so frontend code does not need to handle bearer tokens. A production application
 * can replace this in-memory store with persistent or clustered session storage without changing the
 * OIDC protocol flow.
 */
public final class AppSessionStore {
    public static final String SESSION_COOKIE = "flux_app_session";
    private static final Duration SESSION_TTL = Duration.ofHours(8);
    private static final SecureRandom random = new SecureRandom();
    private static final Map<String, AppSession> sessions = new ConcurrentHashMap<>();

    private AppSessionStore() {
    }

    static AppSession createSession(String idToken, String accessToken, JsonNode claims, Sender sender) {
        prune();
        String sessionId = randomToken();
        AppSession session = new AppSession(
                sessionId, idToken, accessToken, claims, sender, Fluxzero.currentTime().plus(SESSION_TTL));
        sessions.put(sessionId, session);
        return session;
    }

    static Optional<AppSession> session(WebRequest request) {
        prune();
        return WebRequest.getCookie(request.getMetadata(), SESSION_COOKIE)
                .map(cookie -> cookie.getValue())
                .flatMap(AppSessionStore::session);
    }

    public static Optional<Sender> sender(Metadata metadata) {
        prune();
        return WebRequest.getCookie(metadata, SESSION_COOKIE)
                .map(cookie -> cookie.getValue())
                .flatMap(AppSessionStore::session)
                .map(AppSession::sender);
    }

    static void remove(WebRequest request) {
        WebRequest.getCookie(request.getMetadata(), SESSION_COOKIE)
                .map(cookie -> cookie.getValue())
                .ifPresent(sessions::remove);
    }

    static String cookieValue(String name, String value, Duration maxAge, String path) {
        return "%s=%s; Path=%s; Max-Age=%d; HttpOnly; SameSite=Lax%s".formatted(
                name,
                URLEncoder.encode(value, StandardCharsets.UTF_8),
                path,
                maxAge.toSeconds(),
                secureCookieSuffix());
    }

    static String clearCookie(String name, String path) {
        return "%s=; Path=%s; Max-Age=0; HttpOnly; SameSite=Lax%s".formatted(name, path, secureCookieSuffix());
    }

    static String sessionCookie(AppSession session) {
        return cookieValue(SESSION_COOKIE, session.sessionId(), SESSION_TTL, "/");
    }

    static String clearSessionCookie() {
        return clearCookie(SESSION_COOKIE, "/");
    }

    static void clear() {
        sessions.clear();
    }

    private static Optional<AppSession> session(String sessionId) {
        return Optional.ofNullable(sessions.get(sessionId))
                .filter(session -> session.expiresAt().isAfter(Fluxzero.currentTime()));
    }

    private static String randomToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static void prune() {
        Instant now = Fluxzero.currentTime();
        sessions.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
    }

    private static String secureCookieSuffix() {
        return AppAuthProperties.externalBaseUrl().startsWith("https://") ? "; Secure" : "";
    }

    record AppSession(String sessionId,
                      String idToken,
                      String accessToken,
                      JsonNode claims,
                      Sender sender,
                      Instant expiresAt) {
    }
}
