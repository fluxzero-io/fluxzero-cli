package io.fluxzero.tools.account;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class LocalAccountServiceTest {
    @TempDir Path directory;
    private HttpServer idp;
    private LocalAccountService service;
    private final MemoryStore store = new MemoryStore();
    private final AtomicReference<Map<String, String>> authorization = new AtomicReference<>();
    private final AtomicReference<String> subject = new AtomicReference<>("stable-subject");
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicInteger refreshes = new AtomicInteger();
    private final AtomicReference<CountDownLatch> tokenEntered = new AtomicReference<>();
    private final AtomicReference<CountDownLatch> tokenRelease = new AtomicReference<>();
    private final AtomicReference<String> tokenError = new AtomicReference<>();
    private final java.util.concurrent.atomic.AtomicBoolean dropRefreshReply = new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.List<String> presentedRefreshTokens = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final java.util.concurrent.atomic.AtomicBoolean sessionRevoked = new java.util.concurrent.atomic.AtomicBoolean();
    private AccountConfiguration configuration;
    private KeyPair signingKey;

    @AfterEach void close() { if (service != null) service.close(); if (idp != null) idp.stop(0); }

    @Test void statusIsEntirelyLocalAndDoesNotAccessSecureStorageOrCreateFiles() {
        configuration = config(URI.create("https://unreachable.example.test"));
        try (var account = new LocalAccountService(configuration, store, uri -> fail("Browser must remain closed"))) {
            assertEquals("signed_out", account.status().state());
            assertEquals(0, store.reads.get());
            assertEquals(0, store.writes.get());
            assertFalse(Files.exists(directory.resolve(configuration.storageId())));
        }
    }

    @Test void loginIsSharedAcrossProcessesAndNoCredentialReachesMetadataOrPublicResults() throws Exception {
        start(false);
        assertEquals("signed_in", login().state());
        try (var second = new LocalAccountService(configuration, store, uri -> fail("No browser during status"))) {
            assertEquals("Marty", second.status().displayName());
            assertEquals("marty@example.test", second.status().email());
        }
        String publicStatus = AccountHttp.JSON.writeValueAsString(service.status());
        String metadata = Files.readString(directory.resolve(configuration.storageId()).resolve("state.json"));
        assertFalse(publicStatus.contains("access-secret"));
        assertFalse(metadata.contains("access-secret"));
        assertFalse(metadata.contains("refresh-secret"));
        assertFalse(metadata.contains("privateKey"));
        assertEquals(1, store.values.size());
        assertEquals("signed_out", service.logout().state());
        assertTrue(store.values.isEmpty());
    }

    @Test void browserSignOutCompletesAutomaticallyAndCancellationPreservesTheAccount() throws Exception {
        start(true); login();
        var opened = new java.util.concurrent.LinkedBlockingQueue<URI>();
        try (var other = new LocalAccountService(configuration, store, opened::add)) {
            assertEquals("sign_out_everywhere", other.startLogoutEverywhere().pendingAction());
            var destination = opened.poll(5, TimeUnit.SECONDS);
            assertNotNull(destination);
            assertEquals(configuration.sessionsUrl().getPath(), destination.getPath());
            var parameters = query(destination.getRawQuery());
            assertEquals("sign-out-everywhere", parameters.get("action"));
            assertEquals("sign_out_everywhere", service.status().pendingAction());
            completeSignOut(parameters, "cancelled");
            awaitNoBrowserAction(other);
            assertEquals("signed_in", other.status().state());
            assertEquals("Sign-out cancelled.", other.status().message());
            other.startLogoutEverywhere();
            assertNull(other.status().message());
            parameters = query(opened.poll(5, TimeUnit.SECONDS).getRawQuery());
            // A completion hint alone must never sign out a different/still-active account.
            completeSignOut(parameters, "completed");
            assertEquals("signed_in", other.status().state());
            sessionRevoked.set(true);
            awaitNoBrowserAction(other);
            assertEquals("signed_out", service.status().state());
            assertNull(service.status().message());
            assertTrue(store.values.isEmpty());
        }
    }

    private static void completeSignOut(Map<String,String> parameters, String outcome) throws Exception {
        var target = URI.create(parameters.get("completion_uri") + "?state=" + parameters.get("state") + "&outcome=" + outcome);
        assertEquals(200, HttpClient.newHttpClient().send(HttpRequest.newBuilder(target).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode());
    }

    @Test void completedBrowserHintCannotSignOutAnUnrevokedNativeAccount() throws Exception {
        start(true);login();
        var opened = new java.util.concurrent.LinkedBlockingQueue<URI>();
        try (var other = new LocalAccountService(configuration, store, opened::add)) {
            other.startLogoutEverywhere();
            completeSignOut(query(opened.poll(5, TimeUnit.SECONDS).getRawQuery()), "completed");
            awaitNoBrowserAction(other);
            assertEquals("signed_in",other.status().state());
            assertEquals(1,store.values.size());
            assertTrue(other.status().message().contains("could not be confirmed"));
        }
    }

    @Test void browserClosureAfterServerRevocationDoesNotLeaveTheUserWaiting() throws Exception {
        start(true);login();
        var opened = new CountDownLatch(1);
        try (var other = new LocalAccountService(configuration, store, uri -> opened.countDown())) {
            other.startLogoutEverywhere();
            assertTrue(opened.await(5,TimeUnit.SECONDS));
            sessionRevoked.set(true); // The browser closes without delivering the optional completion hint.
            awaitNoBrowserAction(other);
            assertEquals("signed_out",other.status().state());
            assertNull(other.status().message());
        }
    }
    private static void awaitNoBrowserAction(LocalAccountService account) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (account.status().pendingAction() != null && System.nanoTime() < deadline) Thread.sleep(10);
        assertNull(account.status().pendingAction());
    }

    @Test void failedBrowserSignOutCheckPreservesCredentialsAndPendingAction() throws Exception {
        start(true); login();
        try (var other = new LocalAccountService(configuration, store, uri -> {})) {
            other.startLogoutEverywhere();
            idp.stop(0);
            assertEquals("offline", assertThrows(AccountException.class, () -> other.checkSession(false)).code());
            assertEquals("signed_in", other.status().state());
            assertEquals("sign_out_everywhere", other.status().pendingAction());
            assertEquals(1, store.values.size());
        }
    }

    @Test void switchingAccountRequestsFreshLoginAndCancellationPreservesExistingAccount() throws Exception {
        start(true); login();
        var opened = new CountDownLatch(1);
        try (var other = new LocalAccountService(configuration, store, uri -> {authorization.set(query(uri.getRawQuery()));opened.countDown();})) {
            authorization.set(null);
            other.startLogin(true);
            assertTrue(opened.await(5, TimeUnit.SECONDS));
            assertNotNull(authorization.get());
            assertEquals("login", authorization.get().get("prompt"));
            other.cancelLogin();
            assertEquals("signed_in", other.status().state());
            assertEquals("Marty", other.status().displayName());
        }
    }

    @Test void expiredSessionRenewsWithoutOpeningBrowserAndPreservesIdentity() throws Exception {
        start(true);
        login();
        expireProjectionAndSecret();
        assertEquals("signed_in", service.refresh().state());
        assertEquals(1, refreshes.get());
        assertEquals("Marty", service.status().displayName());
        assertEquals(1, store.values.size());
    }

    @Test void offlineRefreshPreservesTheRememberedAccountAndCredentials() throws Exception {
        start(true); login(); expireProjectionAndSecret();
        idp.stop(0);
        AccountException error = assertThrows(AccountException.class, service::refresh);
        assertEquals("offline", error.code());
        assertEquals("signed_in", service.status().state());
        assertEquals(1, store.values.size());
    }

    @Test void concurrentRefreshUsesOneRequestAndOtherProcessesObserveTheSavedResult() throws Exception {
        start(true); login(); expireProjectionAndSecret();
        tokenEntered.set(new CountDownLatch(1)); tokenRelease.set(new CountDownLatch(1));
        var refreshing = CompletableFuture.supplyAsync(service::refresh);
        assertTrue(tokenEntered.get().await(5, TimeUnit.SECONDS));
        try (var second = new LocalAccountService(configuration, store, uri -> fail())) {
            assertEquals("busy", assertThrows(AccountException.class, second::refresh).code());
            tokenRelease.get().countDown();
            assertEquals("signed_in", refreshing.get(5, TimeUnit.SECONDS).state());
            assertEquals("signed_in", second.refresh().state());
        }
        assertEquals(1, refreshes.get());
    }

    @Test void lostRefreshReplyPreservesTheCredentialForAnExplicitRetry() throws Exception {
        start(true); login(); expireProjectionAndSecret();
        String original = new AccountRepository(configuration).read().credentialKey();
        dropRefreshReply.set(true);
        assertEquals("offline", assertThrows(AccountException.class, service::refresh).code());
        assertEquals(original, new AccountRepository(configuration).read().credentialKey());
        assertEquals("signed_in", service.refresh().state());
        assertEquals(2, presentedRefreshTokens.size());
        assertEquals(presentedRefreshTokens.getFirst(), presentedRefreshTokens.getLast());
    }

    @Test void interruptedStorageDoesNotPublishAnIncompleteSession() throws Exception {
        start(true); login(); expireProjectionAndSecret();
        String original = new AccountRepository(configuration).read().credentialKey();
        store.locked = true;
        assertEquals("storage_locked", assertThrows(AccountException.class, service::refresh).code());
        assertEquals(original, new AccountRepository(configuration).read().credentialKey());
        assertEquals(1, store.values.size());
        store.locked = false;
        assertEquals("signed_in", service.refresh().state());
        assertEquals(original, new AccountRepository(configuration).read().credentialKey());
        assertEquals(1, store.values.size());
    }

    @Test void aLostStorageAcknowledgementKeepsTheRotatedTokenAndRepairsMetadataAfterRestart() throws Exception {
        start(true); login(); expireProjectionAndSecret();
        String original = new AccountRepository(configuration).read().credentialKey();
        store.failAfterWrite = true;
        assertThrows(AccountException.class, service::refresh);
        assertEquals(1, new AccountRepository(configuration).read().expiresAt());
        try (var restarted = new LocalAccountService(configuration, store, uri -> fail())) {
            assertEquals("signed_in", restarted.refresh().state());
            assertTrue(restarted.status().expiresAt() > Instant.now().getEpochSecond());
        }
        assertEquals(1, refreshes.get());
        assertEquals(original, new AccountRepository(configuration).read().credentialKey());
        assertEquals(1, store.values.size());
    }

    @Test void offlineLogoutRemovesLocalAccessAndDoesNotClaimServerRevocation() throws Exception {
        start(true); login(); idp.stop(0);
        var status = service.logout();
        assertEquals("signed_out", status.state());
        assertTrue(status.message().contains("could not be confirmed"));
        assertTrue(store.values.isEmpty());
    }

    @Test void logoutDuringRefreshCannotRestoreCredentials() throws Exception {
        start(true); login(); expireProjectionAndSecret();
        tokenEntered.set(new CountDownLatch(1)); tokenRelease.set(new CountDownLatch(1));
        var refreshing = CompletableFuture.runAsync(service::refresh);
        assertTrue(tokenEntered.get().await(5, TimeUnit.SECONDS));
        // The state transaction is independent from the in-flight network request.
        try (var second = new LocalAccountService(configuration, store, uri -> fail())) {
            second.logout();
        }
        tokenRelease.get().countDown();
        refreshing.get(5, TimeUnit.SECONDS);
        assertEquals("signed_out", service.status().state());
        assertTrue(store.values.isEmpty());
    }

    @Test void revokedRefreshRequiresLoginAndNeverExposesServerErrorText() throws Exception {
        start(true); login(); expireProjectionAndSecret();
        tokenError.set("invalid_grant");
        AccountException error = assertThrows(AccountException.class, service::refresh);
        assertEquals("sign_in_required", error.code());
        assertFalse(error.getMessage().contains("server-secret"));
        assertEquals("signed_out", service.status().state());
        assertTrue(store.values.isEmpty());
    }

    @Test void aChangedIdentityCannotBeInstalledByRefresh() throws Exception {
        start(true); login(); expireProjectionAndSecret();
        subject.set("different-subject");
        assertEquals("invalid_identity", assertThrows(AccountException.class, service::refresh).code());
        assertEquals("Marty", service.status().displayName());
    }

    @Test void lockedCredentialStorageDoesNotAffectStatusOrLeakItsError() throws Exception {
        start(false); store.locked = true;
        AccountStatus result = login();
        assertEquals("signed_out", result.state());
        assertTrue(result.message().contains("secure account storage"));
        assertEquals("signed_out", service.status().state());
    }

    @Test void cancellationClosesTheCallbackAndDoesNotChangeExistingLocalState() throws Exception {
        start(false);
        var launched = new CompletableFuture<URI>();
        service.close();
        service = new LocalAccountService(configuration, store, launched::complete);
        service.startLogin();
        URI uri = launched.get(5, TimeUnit.SECONDS);
        assertEquals("signing_in", service.status().state());
        assertEquals("signed_out", service.cancelLogin().state());
        assertEquals("signed_out", service.awaitLogin().state());
        assertTrue(store.values.isEmpty());
        URI callback = URI.create(query(uri.getRawQuery()).get("redirect_uri"));
        try (HttpClient http = HttpClient.newHttpClient()) {
            assertThrows(Exception.class, () -> http.send(HttpRequest.newBuilder(callback).timeout(Duration.ofSeconds(1)).GET().build(), HttpResponse.BodyHandlers.discarding()));
        }
    }

    @Test void cancellationFromAnotherSurfaceReleasesTheOwningCallbackPromptly() throws Exception {
        start(false);
        var launched = new CompletableFuture<URI>();
        service.close(); service = new LocalAccountService(configuration, store, launched::complete);
        service.startLogin();
        URI uri = launched.get(5, TimeUnit.SECONDS);
        try (var second = new LocalAccountService(configuration, store, ignored -> fail())) {second.cancelLogin();}
        var finished = CompletableFuture.supplyAsync(service::awaitLogin);
        assertEquals("signed_out", finished.get(2, TimeUnit.SECONDS).state());
        try (HttpClient http = HttpClient.newHttpClient()) {
            URI callback = URI.create(query(uri.getRawQuery()).get("redirect_uri"));
            assertThrows(Exception.class, () -> http.send(HttpRequest.newBuilder(callback).timeout(Duration.ofSeconds(1)).GET().build(), HttpResponse.BodyHandlers.discarding()));
        }
    }

    @Test void callbackRejectsWrongStateIssuerMethodPathAndDuplicateParameters() throws Exception {
        start(false);
        var launched = new CompletableFuture<URI>();
        service.close(); service = new LocalAccountService(configuration, store, launched::complete);
        service.startLogin();
        var params = query(launched.get(5, TimeUnit.SECONDS).getRawQuery()); authorization.set(params);
        URI callback = URI.create(params.get("redirect_uri"));
        String valid = "?code=test-code&state=" + params.get("state");
        try (HttpClient http = HttpClient.newHttpClient()) {
            for (String suffix : java.util.List.of("?code=test-code&state=wrong", valid + "&state=duplicate", valid + "&iss=https%3A%2F%2Fwrong.example.test")) {
                assertEquals(400, http.send(HttpRequest.newBuilder(URI.create(callback + suffix)).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode());
            }
            assertEquals(400, http.send(HttpRequest.newBuilder(URI.create(callback + valid)).POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding()).statusCode());
            assertEquals(400, http.send(HttpRequest.newBuilder(URI.create(callback + "/other" + valid)).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode());
            assertEquals("signing_in", service.status().state());
            assertEquals(0, requests.get());
            var response = http.send(HttpRequest.newBuilder(URI.create(callback + valid)).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("Return to local development"));
            assertTrue(response.body().contains("terminal or Devboard"));
            assertFalse(response.body().contains("test-code"));
            String script = response.body().split("<script>")[1].split("</script>")[0];
            String hash = Base64.getEncoder().encodeToString(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(script.getBytes(StandardCharsets.UTF_8)));
            assertTrue(response.headers().firstValue("Content-Security-Policy").orElseThrow().contains("'sha256-" + hash + "'"));
            assertTrue(script.contains("/fluxzero/account/complete"));
        }
        assertEquals("signed_in", service.awaitLogin().state());
    }

    private AccountStatus login() { service.startLogin(); return service.awaitLogin(); }

    private AccountConfiguration config(URI issuer) {
        return new AccountConfiguration("test", issuer, "fz-test", "", issuer.resolve("/account"), directory, true, Duration.ofSeconds(10));
    }

    private void start(boolean renewable) throws Exception {
        signingKey = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        idp = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        idp.setExecutor(java.util.concurrent.Executors.newCachedThreadPool(r -> { var t = new Thread(r); t.setDaemon(true); return t; }));
        URI issuer = URI.create("http://127.0.0.1:" + idp.getAddress().getPort());
        configuration = config(issuer);
        idp.createContext("/.well-known/openid-configuration", exchange -> reply(exchange, 200, Map.of(
                "issuer", issuer.toString(), "authorization_endpoint", issuer + "/authorize", "token_endpoint", issuer + "/token",
                "jwks_uri", issuer + "/jwks", "revocation_endpoint", issuer + "/revoke", "userinfo_endpoint", issuer + "/userinfo")));
        idp.createContext("/jwks", exchange -> {
            var rsa = (RSAPublicKey) signingKey.getPublic();
            reply(exchange, 200, Map.of("keys", java.util.List.of(Map.of("kty", "RSA", "kid", "test", "alg", "RS256",
                    "n", unsigned(rsa.getModulus()), "e", unsigned(rsa.getPublicExponent())))));
        });
        idp.createContext("/revoke", exchange -> reply(exchange, 200, Map.of()));
        idp.createContext("/userinfo", exchange -> {
            assertEquals("DPoP access-secret", exchange.getRequestHeaders().getFirst("Authorization"));
            String proof = exchange.getRequestHeaders().getFirst("DPoP");
            var claims = AccountHttp.JSON.readTree(java.util.Base64.getUrlDecoder().decode(proof.split("\\.")[1]));
            assertEquals(AccountCrypto.hash("access-secret"), claims.path("ath").asText());
            assertEquals("GET", claims.path("htm").asText());
            reply(exchange, sessionRevoked.get() ? 401 : 200, sessionRevoked.get() ? Map.of("error", "invalid_token") : Map.of("sub", subject.get()));
        });
        idp.createContext("/token", exchange -> {
            requests.incrementAndGet();
            Map<String, String> form = query(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            assertNotNull(exchange.getRequestHeaders().getFirst("DPoP"));
            boolean refreshing = "refresh_token".equals(form.get("grant_type"));
            if (refreshing) {
                refreshes.incrementAndGet();
                presentedRefreshTokens.add(form.get("refresh_token"));
                if (tokenEntered.get() != null) {
                    tokenEntered.get().countDown();
                    try { assertTrue(tokenRelease.get().await(8, TimeUnit.SECONDS)); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
            } else assertEquals(authorization.get().get("code_challenge"), AccountCrypto.hash(form.get("code_verifier")));
            if (tokenError.get() != null) { reply(exchange, 400, Map.of("error", tokenError.get(), "error_description", "server-secret")); return; }
            if (refreshing && dropRefreshReply.compareAndSet(true, false)) {exchange.close();return;}
            Map<String, Object> token = new HashMap<>(Map.of("access_token", "access-secret", "token_type", "DPoP", "expires_in", 300,
                    "id_token", signedIdentity(issuer, refreshing ? null : authorization.get().get("nonce"))));
            if (renewable) token.put("refresh_token", "refresh-secret-" + refreshes.get());
            reply(exchange, 200, token);
        });
        idp.start();
        service = new LocalAccountService(configuration, store, uri -> {
            Map<String, String> params = query(uri.getRawQuery()); authorization.set(params);
            assertEquals("S256", params.get("code_challenge_method"));
            assertNotNull(params.get("dpop_jkt"));
            URI callback = URI.create(params.get("redirect_uri") + "?code=test-code&state=" + params.get("state"));
            try (HttpClient http = HttpClient.newHttpClient()) {
                assertEquals(200, http.send(HttpRequest.newBuilder(callback).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode());
            } catch (Exception e) { throw new RuntimeException(e); }
        });
    }

    private String signedIdentity(URI issuer, String nonce) {
        try {
            var claims = new HashMap<String, Object>(Map.of("iss", issuer.toString(), "sub", subject.get(), "aud", "fz-test",
                    "iat", Instant.now().getEpochSecond(), "exp", Instant.now().plusSeconds(300).getEpochSecond(),
                    "name", "Marty", "email", "marty@example.test"));
            if (nonce != null) claims.put("nonce", nonce);
            String unsigned = base64(AccountHttp.JSON.writeValueAsBytes(Map.of("alg", "RS256", "kid", "test"))) + "." + base64(AccountHttp.JSON.writeValueAsBytes(claims));
            var signer = Signature.getInstance("SHA256withRSA"); signer.initSign(signingKey.getPrivate());
            signer.update(unsigned.getBytes(StandardCharsets.US_ASCII));
            return unsigned + "." + base64(signer.sign());
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private void expireProjectionAndSecret() throws Exception {
        var repository = new AccountRepository(configuration);
        var state = repository.read();
        byte[] bytes = store.values.get(state.credentialKey());
        var json = (com.fasterxml.jackson.databind.node.ObjectNode) AccountHttp.JSON.readTree(bytes);
        json.put("expiresAt", 1);
        store.values.put(state.credentialKey(), AccountHttp.JSON.writeValueAsBytes(json));
        repository.locked("state", old -> {
            repository.write(new AccountRepository.State(1, old.generation(), old.credentialKey(), old.subject(), old.name(), old.email(),
                    1, old.renewable(), null, 0, null)); return null;
        });
    }
    private static String unsigned(java.math.BigInteger number) {
        byte[] bytes = number.toByteArray();
        return base64(bytes[0] == 0 ? java.util.Arrays.copyOfRange(bytes, 1, bytes.length) : bytes);
    }
    private static String base64(byte[] bytes) { return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private static Map<String, String> query(String raw) {
        var values = new HashMap<String, String>();
        for (String field : raw.split("&")) { String[] pair = field.split("=", 2); values.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8), URLDecoder.decode(pair[1], StandardCharsets.UTF_8)); }
        return values;
    }
    private static void reply(com.sun.net.httpserver.HttpExchange exchange, int status, Object body) throws java.io.IOException {
        byte[] bytes = AccountHttp.JSON.writeValueAsBytes(body);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }
    private static final class MemoryStore implements SecureCredentialStore {
        final Map<String, byte[]> values = new ConcurrentHashMap<>();
        final AtomicInteger reads = new AtomicInteger(), writes = new AtomicInteger();
        boolean locked, failAfterWrite;
        public Optional<byte[]> read(String key, boolean interactive) { reads.incrementAndGet(); return Optional.ofNullable(values.get(key)).map(byte[]::clone); }
        public void write(String key, byte[] value, boolean interactive) {
            writes.incrementAndGet();
            if (locked) throw new AccountException("storage_locked", "Allow access to your secure account storage to continue signing in.");
            values.put(key, value.clone());
            if (failAfterWrite) {failAfterWrite = false;throw new AccountException("storage_unavailable", "Secure storage could not confirm the update.");}
        }
        public void delete(String key, boolean interactive) { values.remove(key); }
    }
}
