package io.fluxzero.tools.account;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Shared local account lifecycle. Construction and status never contact the network or credential store. */
public final class LocalAccountService implements AutoCloseable {
    private final AccountConfiguration configuration;
    private final SecureCredentialStore store;
    private final BrowserLauncher browser;
    private final AccountRepository repository;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "fluxzero-account"); thread.setDaemon(true); return thread;
    });
    private final AtomicReference<HttpServer> callbackServer = new AtomicReference<>();
    private final AtomicReference<CompletableFuture<String>> callbackCode = new AtomicReference<>();
    private volatile CompletableFuture<AccountStatus> login;
    private volatile boolean closed;

    record SecretSession(String accessToken, String refreshToken, String tokenType, AccountCrypto.ProofKey proofKey,
                         String subject, long expiresAt, URI tokenEndpoint, URI revocationEndpoint) {
        @Override public String toString() { return "Account credentials [redacted]"; }
    }
    record Endpoints(URI authorization, URI token, URI keys, URI revocation, URI userInfo) {}

    public LocalAccountService(AccountConfiguration configuration, SecureCredentialStore store, BrowserLauncher browser) {
        this.configuration = configuration; this.store = store; this.browser = browser;
        repository = new AccountRepository(configuration);
    }

    public static LocalAccountService system() {
        return new LocalAccountService(AccountConfiguration.defaults(), SecureCredentialStore.system(), BrowserLauncher.system());
    }

    public AccountStatus status() {
        try { return repository.status(); }
        catch (AccountException e) {
            return new AccountStatus("unavailable", null, null, configuration.accountUrl().toString(), configuration.environment(),
                    0, false, null, e.getMessage());
        }
    }

    /** Starts a bounded asynchronous login. No code, token, verifier or private key is returned. */
    public AccountStatus startLogin() { return startLogin(false); }

    /** Request a fresh identity ceremony instead of reusing browser SSO. */
    public synchronized AccountStatus startLogin(boolean useAnotherAccount) {
        if (closed) throw new AccountException("account_closed", "This account connection has closed. Try again from an active Devboard or CLI.");
        createdOperation = null;
        AccountRepository.State state = repository.locked("state", previous -> {
            if (previous.operationId() != null && previous.loginDeadline() > Instant.now().getEpochSecond()) return previous;
            var next = new AccountRepository.State(1, previous.generation() + 1, previous.credentialKey(), previous.subject(),
                    previous.name(), previous.email(), previous.expiresAt(), previous.renewable(), UUID.randomUUID().toString(),
                    Instant.now().plus(configuration.loginTimeout()).getEpochSecond(), null);
            repository.write(next);
            createdOperation = next.operationId();
            return next;
        });
        if (login == null || login.isDone()) {
            // Only the process that owns this operation may run its callback; a second process merely observes it.
            if (ownedOperation == null && createdHere(state)) {
                ownedOperation = state.operationId();
                login = CompletableFuture.supplyAsync(() -> performLogin(state, useAnotherAccount), worker);
            }
        }
        return repository.status(state);
    }

    private volatile String ownedOperation;
    private String createdOperation;
    private boolean createdHere(AccountRepository.State state) {
        // startLogin marks ownership under the repository lock below; old operations remain observable only.
        return state.operationId() != null && state.operationId().equals(createdOperation);
    }

    public AccountStatus awaitLogin() {
        var pending = login;
        if (pending == null) return status();
        try { return pending.get(configuration.loginTimeout().plusSeconds(15).toMillis(), TimeUnit.MILLISECONDS); }
        catch (java.util.concurrent.CancellationException e) { return status(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); cancelLogin(); return status(); }
        catch (Exception e) { cancelLogin(); return status(); }
    }

    public AccountStatus cancelLogin() {
        repository.locked("state", previous -> {
            if (previous.operationId() == null) return null;
            repository.write(new AccountRepository.State(1, previous.generation() + 1, previous.credentialKey(), previous.subject(),
                    previous.name(), previous.email(), previous.expiresAt(), previous.renewable(), null, 0,
                    "Sign-in was cancelled. You can continue working locally."));
            return null;
        });
        HttpServer server = callbackServer.getAndSet(null);
        if (server != null) server.stop(0);
        var code = callbackCode.getAndSet(null);
        if (code != null) code.completeExceptionally(new java.util.concurrent.CancellationException());
        var pending = login;
        if (pending != null) pending.cancel(true);
        ownedOperation = null;
        return status();
    }

    private AccountStatus performLogin(AccountRepository.State initial, boolean useAnotherAccount) {
        ExecutorService callbackWorker = Executors.newSingleThreadExecutor(r -> {
            var thread = new Thread(r, "fluxzero-account-callback"); thread.setDaemon(true); return thread;
        });
        try (AccountHttp http = new AccountHttp()) {
            Endpoints endpoints = endpoints(http);
            requireCurrent(initial);
            var key = AccountCrypto.key();
            String verifier = AccountCrypto.random(), nonce = AccountCrypto.random(), state = AccountCrypto.random();
            CompletableFuture<String> code = new CompletableFuture<>();
            callbackCode.set(code);
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 4);
            callbackServer.set(server);
            URI redirect = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/fluxzero/account/callback");
            server.createContext("/fluxzero/account/callback", exchange -> {
                boolean accepted = false;
                String text = "This sign-in response could not be used. Return to your terminal or Devboard and try again.";
                try {
                    if (!exchange.getRequestMethod().equals("GET") || !redirect.getPath().equals(exchange.getRequestURI().getPath())
                            || !redirect.getAuthority().equals(exchange.getRequestHeaders().getFirst("Host"))) throw new IllegalArgumentException();
                    Map<String, String> query = query(exchange.getRequestURI().getRawQuery());
                    if (!AccountCrypto.constantEquals(state, query.getOrDefault("state", ""))) throw new IllegalArgumentException();
                    if (query.containsKey("iss") && !configuration.issuer().toString().equals(query.get("iss"))) throw new IllegalArgumentException();
                    if (query.containsKey("error")) {
                        code.completeExceptionally(new AccountException("cancelled", "Sign-in was not completed. You can try again whenever you need Cloud access."));
                        text = "Sign-in was not completed. You can close this tab and return to your terminal or Devboard.";
                    } else if (!query.getOrDefault("code", "").isBlank() && code.complete(query.get("code"))) {
                        accepted = true;
                        text = "You can close this tab and continue in your terminal or Devboard.";
                    }
                } catch (RuntimeException ignored) {}
                byte[] body = callbackPage(text).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
                exchange.getResponseHeaders().set("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; script-src 'sha256-UxVXWyAt9U/Kult2HhuIFYy6Qb9b3amlNkkrXDu38Zg='; frame-ancestors 'none'");
                exchange.getResponseHeaders().set("Cache-Control", "no-store");
                exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
                exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
                exchange.sendResponseHeaders(accepted ? 200 : 400, body.length);
                try (var output = exchange.getResponseBody()) { output.write(body); }
            });
            server.setExecutor(callbackWorker);
            server.start();
            Map<String, String> request = new LinkedHashMap<>();
            request.put("client_id", configuration.clientId()); request.put("response_type", "code");
            request.put("redirect_uri", redirect.toString());
            request.put("scope", "openid profile email" + (configuration.requestOfflineAccess() ? " offline_access" : ""));
            request.put("state", state); request.put("nonce", nonce);
            request.put("code_challenge", AccountCrypto.hash(verifier)); request.put("code_challenge_method", "S256");
            request.put("dpop_jkt", key.thumbprint());
            if (useAnotherAccount) request.put("prompt", "login");
            if (!configuration.resource().isBlank()) request.put("resource", configuration.resource());
            requireCurrent(initial);
            browser.open(URI.create(endpoints.authorization() + "?" + AccountHttp.form(request)));
            String authorizationCode = awaitCode(code, initial);
            Map<String, String> tokenRequest = new LinkedHashMap<>();
            tokenRequest.put("grant_type", "authorization_code"); tokenRequest.put("client_id", configuration.clientId());
            tokenRequest.put("code", authorizationCode); tokenRequest.put("redirect_uri", redirect.toString());
            tokenRequest.put("code_verifier", verifier);
            if (!configuration.resource().isBlank()) tokenRequest.put("resource", configuration.resource());
            var token = token(http, endpoints.token(), tokenRequest, key);
            var jwks = successful(http.get(endpoints.keys()));
            var identity = AccountCrypto.verifyIdentity(required(token, "id_token"), jwks, configuration, nonce, null);
            SecretSession session = session(token, key, identity.path("sub").asText(), endpoints, null);
            install(initial, session, identity, false, true);
        } catch (Exception e) { fail(initial, customerFailure(e)); }
        finally {
            HttpServer server = callbackServer.getAndSet(null);
            if (server != null) server.stop(0);
            callbackCode.set(null);
            callbackWorker.shutdownNow();
            synchronized (this) {
                if (initial.operationId().equals(ownedOperation)) ownedOperation = null;
            }
        }
        return status();
    }

    private String awaitCode(CompletableFuture<String> code, AccountRepository.State initial) throws Exception {
        long deadline = System.nanoTime() + configuration.loginTimeout().toNanos();
        while (System.nanoTime() < deadline) {
            requireCurrent(initial);
            try { return code.get(200, TimeUnit.MILLISECONDS); }
            catch (java.util.concurrent.TimeoutException ignored) { /* Observe cancellation from another local surface. */ }
        }
        throw new AccountException("login_timeout", "Sign-in timed out. Try signing in again.");
    }

    private void install(AccountRepository.State expected, SecretSession session, JsonNode identity, boolean renewing, boolean interactive) throws Exception {
        if (closed || repository.read().generation() != expected.generation()) return;
        // Keychain updates are atomic. Keep the login's item during refresh so its OS access approvals survive.
        // A fresh login always gets a separate item, isolating late refresh writes from an account switch.
        String key = renewing ? expected.credentialKey() : configuration.storageId() + "-" + UUID.randomUUID();
        byte[] bytes = AccountHttp.JSON.writeValueAsBytes(session);
        try { store.write(key, bytes, interactive); } finally { Arrays.fill(bytes, (byte) 0); }
        boolean installed = false;
        try {
            installed = repository.locked("state", current -> {
                if (current.generation() != expected.generation()) return false;
                repository.write(new AccountRepository.State(1, current.generation(), key, session.subject(),
                        identity.path("name").asText(current.name() == null ? "Fluxzero account" : current.name()),
                        identity.path("email").asText(current.email()), session.expiresAt(), session.refreshToken() != null,
                        null, 0, null));
                return true;
            });
        } finally {
            if (!installed && !java.util.Objects.equals(repository.read().credentialKey(), key)) discard(key);
            else if (installed && !java.util.Objects.equals(expected.credentialKey(), key)) discard(expected.credentialKey());
        }
    }

    /** Explicit user action; local status never invokes renewal. */
    public AccountStatus refresh() { return refresh(true); }

    private AccountStatus refresh(boolean interactive) {
        try {
            return repository.locked("refresh", ignored -> {
                var state = repository.read();
                if (state.credentialKey() == null) return status();
                if (state.operationId() != null && state.loginDeadline() > Instant.now().getEpochSecond())
                    throw new AccountException("busy", "Finish or cancel your current sign-in first.");
                SecretSession session = load(state, interactive);
                if (session.expiresAt() > Instant.now().plusSeconds(30).getEpochSecond()) {
                    repository.locked("state", current -> {
                        if (current.generation() == state.generation() && java.util.Objects.equals(current.credentialKey(), state.credentialKey()))
                            repository.write(new AccountRepository.State(1, current.generation(), current.credentialKey(), current.subject(),
                                    current.name(), current.email(), session.expiresAt(), session.refreshToken() != null, null, 0, null));
                        return null;
                    });
                    return status();
                }
                if (session.refreshToken() == null) throw new AccountException("sign_in_required", "Sign in again when you need Cloud access.");
                try (AccountHttp http = new AccountHttp()) {
                    var endpoints = endpoints(http);
                    Map<String, String> request = new LinkedHashMap<>();
                    request.put("grant_type", "refresh_token"); request.put("client_id", configuration.clientId());
                    request.put("refresh_token", session.refreshToken());
                    if (!configuration.resource().isBlank()) request.put("resource", configuration.resource());
                    JsonNode token = token(http, endpoints.token(), request, session.proofKey());
                    JsonNode identity = token.hasNonNull("id_token")
                            ? AccountCrypto.verifyIdentity(required(token, "id_token"), successful(http.get(endpoints.keys())), configuration, null, session.subject())
                            : AccountHttp.JSON.createObjectNode();
                    install(state, session(token, session.proofKey(), session.subject(), endpoints, session.refreshToken()), identity, true, interactive);
                } catch (AccountException e) {
                    if ("sign_in_required".equals(e.code())) repository.locked("state", current -> {
                        if (current.generation() == state.generation() && java.util.Objects.equals(current.credentialKey(), state.credentialKey())) {
                            repository.write(current.signedOut(repository.browserAction(current) == null ? e.getMessage() : null)); discard(current.credentialKey());
                        }
                        return null;
                    });
                    throw e;
                } catch (Exception e) { throw new AccountException("storage_unavailable", "Your renewed sign-in could not be saved. Try signing in again."); }
                return status();
            });
        } catch (AccountException e) { throw e; }
    }

    /** Opens a focused confirmation backed by the IDP's existing browser-authenticated logout-all action. */
    public synchronized AccountStatus startLogoutEverywhere() {
        if (closed) throw new AccountException("account_closed", "This account connection has closed. Try again from an active Devboard or CLI.");
        repository.locked("state", state -> {
            if (state.credentialKey() == null || state.operationId() != null && state.loginDeadline() > Instant.now().getEpochSecond())
                throw new AccountException("sign_in_required", "Sign in before managing your signed-in devices.");
            var pending = repository.browserAction(state);
            if (pending != null) return pending;
            var action = new AccountRepository.BrowserAction(1, UUID.randomUUID().toString(),
                    "sign_out_everywhere", state.generation(), state.credentialKey(),
                    Instant.now().plus(configuration.loginTimeout()).getEpochSecond());
            repository.write(new AccountRepository.State(1, state.generation(), state.credentialKey(), state.subject(),
                    state.name(), state.email(), state.expiresAt(), state.renewable(), null, 0, null));
            repository.writeBrowserAction(action);
            worker.execute(() -> performLogoutEverywhere(action));
            return action;
        });
        return status();
    }

    private void performLogoutEverywhere(AccountRepository.BrowserAction action) {
        String message = "Sign-out wasn't completed. Please try again.";
        try (var callback = new AccountLogoutCallback(action.id())) {
            String query = AccountHttp.form(Map.of("action", "sign-out-everywhere", "completion_uri", callback.uri().toString(), "state", action.id()));
            browser.open(URI.create(configuration.sessionsUrl() + (configuration.sessionsUrl().getQuery() == null ? "?" : "&") + query));
            long nextCheck = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!closed && Instant.now().getEpochSecond() < action.expiresAt()) {
                var current = repository.browserAction(repository.read());
                if (current == null || !action.id().equals(current.id())) return;
                String outcome = callback.await();
                if (outcome == null) {
                    // Also finish if the browser was closed after confirmation or followed an upstream logout.
                    if (System.nanoTime() >= nextCheck) {
                        try {if (confirmNativeSignOut(action)) return;}
                        catch (AccountException ignored) { /* Preserve access while waiting for the user's browser. */ }
                        nextCheck = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                    }
                    continue;
                }
                if ("cancelled".equals(outcome)) {message = "Sign-out cancelled.";break;}
                // The browser supplies only a hint. Verify this native account, including asynchronous revocation.
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                do {
                    if (confirmNativeSignOut(action)) return;
                    Thread.sleep(250);
                } while (!closed && System.nanoTime() < deadline);
                message = "Sign-out could not be confirmed for this account. Please try again.";
                break;
            }
        } catch (InterruptedException ignored) {Thread.currentThread().interrupt();}
        catch (AccountException failure) {repository.recordFailure(failure.code());message = failure.getMessage();}
        catch (Exception ignored) { /* Keep the recoverable account message scoped to this explicit operation. */ }
        finally {
            if (!closed) {
                String notice = message;
                repository.locked("state", state -> {
                    var pending = repository.browserAction(state);
                    if (pending != null && action.id().equals(pending.id()) || pending == null
                            && state.generation() == action.generation() && java.util.Objects.equals(state.credentialKey(), action.credentialKey())
                            && Instant.now().getEpochSecond() >= action.expiresAt()) {
                        repository.write(new AccountRepository.State(1, state.generation(), state.credentialKey(), state.subject(),
                                state.name(), state.email(), state.expiresAt(), state.renewable(), state.operationId(), state.loginDeadline(), notice));
                        repository.writeBrowserAction(null);
                    }
                    return null;
                });
            }
        }
    }

    private boolean confirmNativeSignOut(AccountRepository.BrowserAction action) {
        if (!"signed_out".equals(checkSession(false).state())) return false;
        repository.locked("state", state -> {
            if (state.generation() == action.generation() + 1 && state.credentialKey() == null && state.operationId() == null)
                repository.write(new AccountRepository.State(1, state.generation(), null, null, null, null, 0, false, null, 0, null));
            return null;
        });
        return true;
    }

    public AccountStatus cancelBrowserAction() {
        repository.locked("state", state -> { repository.writeBrowserAction(null); return null; });
        return status();
    }

    /** Explicit account action. Passive status never validates credentials or contacts the issuer. */
    public AccountStatus checkSession(boolean interactive) {
        AccountRepository.State expected = repository.read();
        if (expected.credentialKey() == null) return status();
        try {
            refresh(interactive);
            return repository.locked("refresh", ignored -> {
                var current = repository.read();
                if (current.credentialKey() == null || current.generation() != expected.generation()) return status();
                var session = load(current, interactive);
                try (AccountHttp http = new AccountHttp()) {
                    URI endpoint = endpoints(http).userInfo();
                    if (endpoint == null) throw invalidService();
                    var result = http.get(endpoint, session.accessToken(), AccountCrypto.proof(session.proofKey(), endpoint, "GET", null, session.accessToken()));
                    if (result.status() == 401 && "invalid_token".equals(result.body().path("error").asText()))
                        throw new AccountException("sign_in_required", "Your sign-in has ended. Sign in again when you need Cloud access.");
                    JsonNode identity = successful(result);
                    if (!identity.path("sub").isTextual() || !AccountCrypto.constantEquals(session.subject(), identity.path("sub").asText()))
                        throw new AccountException("invalid_identity", "Your sign-in could not be verified. Please try signing in again.");
                }
                return status();
            });
        } catch (AccountException failure) {
            if (!"sign_in_required".equals(failure.code())) throw failure;
            repository.locked("state", current -> {
                if (current.generation() == expected.generation() && java.util.Objects.equals(current.credentialKey(), expected.credentialKey())) {
                    repository.write(current.signedOut(null)); discard(current.credentialKey());
                }
                return null;
            });
            return status();
        }
    }

    public AccountStatus logout() {
        AccountRepository.State previous = repository.locked("state", state -> { repository.write(state.signedOut(null)); return state; });
        HttpServer server = callbackServer.getAndSet(null);
        if (server != null) server.stop(0);
        var code = callbackCode.getAndSet(null);
        if (code != null) code.completeExceptionally(new java.util.concurrent.CancellationException());
        String notice = null;
        if (previous.credentialKey() != null) {
            try {
                SecretSession session = load(previous, false);
                if (session.revocationEndpoint() != null) {
                    var fields = Map.of("client_id", configuration.clientId(), "token",
                            session.refreshToken() == null ? session.accessToken() : session.refreshToken());
                    try (AccountHttp http = new AccountHttp()) {
                        var response = http.post(session.revocationEndpoint(), fields,
                                AccountCrypto.proof(session.proofKey(), session.revocationEndpoint(), "POST", null));
                        if (response.status() != 200) throw new IllegalStateException();
                    }
                } else throw new IllegalStateException();
            } catch (Exception e) {
                notice = "You are signed out on this computer. Ending the session at Fluxzero could not be confirmed; you can review sessions in your account.";
            } finally { discard(previous.credentialKey()); }
        }
        if (notice != null) {
            String message = notice;
            repository.locked("state", state -> {
                if (state.generation() == previous.generation() + 1) repository.write(state.signedOut(message));
                return null;
            });
        }
        return status();
    }

    private SecretSession load(AccountRepository.State state, boolean interactive) {
        byte[] bytes = store.read(state.credentialKey(), interactive)
                .orElseThrow(() -> new AccountException("sign_in_required", "Sign in again when you need Cloud access."));
        try {
            var session = AccountHttp.JSON.readValue(bytes, SecretSession.class);
            if (!java.util.Objects.equals(state.subject(), session.subject()))
                throw new AccountException("sign_in_required", "Sign in again when you need Cloud access.");
            return session;
        }
        catch (Exception e) { throw new AccountException("sign_in_required", "Sign in again when you need Cloud access."); }
        finally { Arrays.fill(bytes, (byte) 0); }
    }
    private void discard(String key) { if (key != null) try { store.delete(key, false); } catch (RuntimeException ignored) {} }

    private void fail(AccountRepository.State initial, AccountException error) {
        repository.recordFailure(error.code());
        try { repository.locked("state", current -> {
            if (current.generation() == initial.generation()) repository.write(new AccountRepository.State(1,
                    current.generation(), current.credentialKey(), current.subject(), current.name(), current.email(),
                    current.expiresAt(), current.renewable(), null, 0, error.getMessage()));
            return null;
        }); } catch (RuntimeException ignored) { /* Account errors must never escape to the workspace lifecycle. */ }
    }

    private Endpoints endpoints(AccountHttp http) {
        String base = configuration.issuer().toString().replaceAll("/$", "");
        JsonNode discovery = successful(http.get(URI.create(base + "/.well-known/openid-configuration")));
        if (!configuration.issuer().toString().equals(discovery.path("issuer").asText())) throw invalidService();
        return new Endpoints(endpoint(discovery, "authorization_endpoint"), endpoint(discovery, "token_endpoint"),
                endpoint(discovery, "jwks_uri"), discovery.hasNonNull("revocation_endpoint") ? endpoint(discovery, "revocation_endpoint") : null,
                discovery.hasNonNull("userinfo_endpoint") ? endpoint(discovery, "userinfo_endpoint") : null);
    }
    private URI endpoint(JsonNode discovery, String name) {
        try {
            URI value = URI.create(required(discovery, name));
            AccountConfiguration.requireTrustedTransport(value);
            URI issuer = configuration.issuer();
            if (!issuer.getScheme().equals(value.getScheme()) || !issuer.getAuthority().equals(value.getAuthority())
                    || value.getRawQuery() != null || value.getRawFragment() != null) throw invalidService();
            return value;
        } catch (RuntimeException e) { throw invalidService(); }
    }
    private static JsonNode token(AccountHttp http, URI target, Map<String, String> request, AccountCrypto.ProofKey key) {
        var response = http.post(target, request, AccountCrypto.proof(key, target, "POST", null));
        if ((response.status() == 400 || response.status() == 401) && response.nonce() != null
                && "use_dpop_nonce".equals(response.body().path("error").asText()))
            response = http.post(target, request, AccountCrypto.proof(key, target, "POST", response.nonce()));
        if (response.status() != 200) {
            if ("invalid_grant".equals(response.body().path("error").asText()))
                throw new AccountException("sign_in_required", "Your sign-in has ended. Sign in again when you need Cloud access.");
            String category = switch (response.body().path("error").asText()) {
                case "invalid_dpop_proof", "invalid_client", "invalid_request", "unauthorized_client", "invalid_scope", "use_dpop_nonce" -> response.body().path("error").asText();
                default -> "rejected";
            };
            throw new AccountException("token_" + category, "Sign-in could not be completed. Try again shortly; local development is still available.");
        }
        return response.body();
    }
    private static SecretSession session(JsonNode token, AccountCrypto.ProofKey key, String subject, Endpoints endpoints, String previousRefresh) {
        String type = required(token, "token_type");
        if (!"DPoP".equalsIgnoreCase(type)) throw new AccountException("token_binding_missing", "Your sign-in could not be verified. Please try signing in again.");
        long lifetime = token.path("expires_in").asLong(0);
        if (lifetime <= 0 || lifetime > 86400) throw invalidService();
        return new SecretSession(required(token, "access_token"), token.path("refresh_token").asText(previousRefresh), type,
                key, subject, Instant.now().plusSeconds(lifetime).getEpochSecond(), endpoints.token(), endpoints.revocation());
    }
    private static JsonNode successful(AccountHttp.Reply response) {
        if (response.status() != 200 || !response.body().isObject()) throw invalidService();
        return response.body();
    }
    private static String required(JsonNode json, String field) {
        if (!json.path(field).isTextual() || json.path(field).asText().isBlank()) throw invalidService();
        return json.path(field).asText();
    }
    static Map<String, String> query(String raw) {
        if (raw == null || raw.length() > 16384) throw new IllegalArgumentException();
        Map<String, String> result = new LinkedHashMap<>();
        for (String parameter : raw.split("&")) {
            String[] pair = parameter.split("=", 2);
            if (pair.length != 2 || result.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                    URLDecoder.decode(pair[1], StandardCharsets.UTF_8)) != null) throw new IllegalArgumentException();
        }
        return result;
    }
    private static AccountException invalidService() { return new AccountException("service_unavailable", "Sign-in could not be completed. Try again shortly; local development is still available."); }
    private void requireCurrent(AccountRepository.State expected) {
        if (closed || repository.read().generation() != expected.generation()) throw new java.util.concurrent.CancellationException();
    }
    static String callbackPage(String message) {
        return """
                <!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
                <title>Return to local development</title><style>
                :root{color-scheme:light dark;font-family:system-ui,sans-serif;background:#f3f6fa;color:#182635}
                body{min-height:90vh;display:grid;place-items:center;margin:20px}main{max-width:420px;padding:36px;border:1px solid #cbd5e1;border-radius:16px;background:#fff}
                .brand{font-size:16px;font-weight:650;color:#185a86}h1{font-size:24px;margin:24px 0 16px}p{line-height:1.6;margin:0}
                @media(prefers-color-scheme:dark){:root{background:#111923;color:#e7edf5}main{background:#1b2634;border-color:#4b5d73}.brand{color:#9bd4f4}}
                </style><main><div class="brand">Fluxzero</div><h1>Return to local development</h1><p>
                """ + message + "</p></main><script>history.replaceState(null,'','/fluxzero/account/complete');</script></html>";
    }
    private static AccountException customerFailure(Exception error) {
        Throwable value = error;
        while (value.getCause() != null) value = value.getCause();
        return value instanceof AccountException expected ? expected
                : new AccountException("sign_in_failed", "Sign-in was not completed. Try again when you need Cloud access.");
    }
    @Override public void close() {
        closed = true;
        if (ownedOperation != null) {
            try { cancelLogin(); } catch (RuntimeException ignored) {}
        }
        HttpServer server = callbackServer.getAndSet(null);
        if (server != null) server.stop(0);
        worker.shutdownNow();
    }
}
