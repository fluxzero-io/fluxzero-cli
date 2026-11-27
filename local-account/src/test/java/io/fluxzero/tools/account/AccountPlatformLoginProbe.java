package io.fluxzero.tools.account;

import com.sun.net.httpserver.HttpServer;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Synthetic loopback issuer and callback driver. Never contacts a live identity provider or sends mail. */
public final class AccountPlatformLoginProbe {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected temporary metadata directory");
        Path directory = Path.of(args[0]);
        var store = SecureCredentialStore.system();
        var authorization = new AtomicReference<Map<String, String>>();
        var revoked = new AtomicBoolean();
        KeyPair signingKey = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        HttpServer issuer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        URI origin = URI.create("http://127.0.0.1:" + issuer.getAddress().getPort());
        var configuration = new AccountConfiguration("synthetic-platform", origin, "synthetic-native", "",
                origin.resolve("/account"), directory, true, Duration.ofSeconds(15));
        issuer.createContext("/.well-known/openid-configuration", request -> reply(request, Map.of(
                "issuer", origin.toString(), "authorization_endpoint", origin + "/authorize", "token_endpoint", origin + "/token",
                "jwks_uri", origin + "/keys", "revocation_endpoint", origin + "/revoke", "userinfo_endpoint", origin + "/userinfo")));
        issuer.createContext("/keys", request -> {
            var rsa = (RSAPublicKey) signingKey.getPublic();
            reply(request, Map.of("keys", List.of(Map.of("kty", "RSA", "kid", "synthetic", "alg", "RS256",
                    "n", unsigned(rsa.getModulus()), "e", unsigned(rsa.getPublicExponent())))));
        });
        issuer.createContext("/token", request -> {
            var form = query(new String(request.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            boolean refresh = "refresh_token".equals(form.get("grant_type"));
            require(request.getRequestHeaders().getFirst("DPoP") != null, "Token request has no proof");
            if (!refresh) require(AccountCrypto.hash(form.get("code_verifier")).equals(authorization.get().get("code_challenge")), "PKCE mismatch");
            else require(form.get("refresh_token").equals("synthetic-refresh"), "Refresh mismatch");
            reply(request, Map.of("access_token", "synthetic-access", "refresh_token", "synthetic-refresh",
                    "token_type", "DPoP", "expires_in", refresh ? 300 : 1,
                    "id_token", identity(signingKey, origin, refresh ? null : authorization.get().get("nonce"))));
        });
        issuer.createContext("/userinfo", request -> {
            require("DPoP synthetic-access".equals(request.getRequestHeaders().getFirst("Authorization")), "UserInfo authentication mismatch");
            require(request.getRequestHeaders().getFirst("DPoP") != null, "UserInfo has no proof");
            reply(request, Map.of("sub", "synthetic-subject"));
        });
        issuer.createContext("/revoke", request -> {
            require(request.getRequestHeaders().getFirst("DPoP") != null, "Revocation has no proof");
            revoked.set(true); reply(request, Map.of());
        });
        BrowserLauncher callbackDriver = uri -> {
            authorization.set(query(uri.getRawQuery()));
            var parameters = authorization.get();
            require(parameters.get("scope").contains("offline_access"), "Offline consent not requested");
            require(parameters.get("dpop_jkt") != null, "Authorization has no proof key");
            try (HttpClient client = HttpClient.newHttpClient()) {
                URI callback = URI.create(parameters.get("redirect_uri") + "?code=synthetic-code&state=" + parameters.get("state"));
                var response = client.send(HttpRequest.newBuilder(callback).timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.discarding());
                require(response.statusCode() == 200, "Loopback callback failed");
            } catch (Exception e) { throw new IllegalStateException("Synthetic callback failed"); }
        };
        issuer.start();
        try {
            try (var first = new LocalAccountService(configuration, store, callbackDriver)) {
                first.startLogin(); require(first.awaitLogin().state().equals("signed_in"), "Synthetic login failed");
                require(first.status().renewable(), "No renewable session stored");
            }
            try (var restarted = new LocalAccountService(configuration, store, callbackDriver)) {
                require(restarted.status().state().equals("signed_in"), "Restart lost account status");
                Instant deadline = Instant.now().plusSeconds(5);
                while (Instant.now().getEpochSecond() < restarted.status().expiresAt()) {
                    require(Instant.now().isBefore(deadline), "Synthetic token did not expire"); Thread.sleep(20);
                }
                var renewed = restarted.refresh();
                require(renewed.state().equals("signed_in") && renewed.expiresAt() > Instant.now().plusSeconds(100).getEpochSecond(), "Refresh failed");
                require(restarted.checkSession(false).state().equals("signed_in"), "Restarted session verification failed");
                require(restarted.logout().state().equals("signed_out") && revoked.get(), "Local/online logout failed");
            }
            System.out.println("Synthetic platform login, process-instance restart, expiry, DPoP refresh, verification and logout passed.");
        } finally {
            issuer.stop(0);
            var repository = new AccountRepository(configuration);
            var key = repository.read().credentialKey();
            if (key != null) store.delete(key, false);
            if (Files.exists(directory)) try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static String identity(KeyPair key, URI issuer, String nonce) {
        try {
            var claims = new HashMap<String, Object>(Map.of("iss", issuer.toString(), "sub", "synthetic-subject", "aud", "synthetic-native",
                    "iat", Instant.now().getEpochSecond(), "exp", Instant.now().plusSeconds(300).getEpochSecond(),
                    "name", "Synthetic Account", "email", "synthetic@example.test"));
            if (nonce != null) claims.put("nonce", nonce);
            String payload = base64(AccountHttp.JSON.writeValueAsBytes(Map.of("alg", "RS256", "kid", "synthetic"))) + "." + base64(AccountHttp.JSON.writeValueAsBytes(claims));
            Signature signer = Signature.getInstance("SHA256withRSA"); signer.initSign(key.getPrivate());
            signer.update(payload.getBytes(StandardCharsets.US_ASCII));
            return payload + "." + base64(signer.sign());
        } catch (Exception e) { throw new IllegalStateException("Synthetic issuer could not sign identity"); }
    }
    private static void reply(com.sun.net.httpserver.HttpExchange exchange, Object body) throws java.io.IOException {
        byte[] value = AccountHttp.JSON.writeValueAsBytes(body); exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, value.length); exchange.getResponseBody().write(value); exchange.close();
    }
    private static Map<String, String> query(String query) {
        var result = new HashMap<String, String>();
        for (String field : query.split("&")) { var pair = field.split("=", 2); result.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8), URLDecoder.decode(pair[1], StandardCharsets.UTF_8)); }
        return result;
    }
    private static String unsigned(java.math.BigInteger value) {
        byte[] bytes = value.toByteArray(); return base64(bytes[0] == 0 ? java.util.Arrays.copyOfRange(bytes, 1, bytes.length) : bytes);
    }
    private static String base64(byte[] value) { return Base64.getUrlEncoder().withoutPadding().encodeToString(value); }
}
