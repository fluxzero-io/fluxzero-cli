package io.fluxzero.tools.account;

import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Non-secret completion hint. Only a subsequent proof-bound session check can confirm sign-out. */
final class AccountLogoutCallback implements AutoCloseable {
    static final String PATH = "/fluxzero/account/logout/callback";
    private final HttpServer server;
    private final CompletableFuture<String> outcome = new CompletableFuture<>();

    AccountLogoutCallback(String state) throws java.io.IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 2);
        server.createContext(PATH, request -> {
            String result = null;
            try {
                var query = LocalAccountService.query(request.getRequestURI().getRawQuery());
                if ("GET".equals(request.getRequestMethod()) && PATH.equals(request.getRequestURI().getPath())
                        && AccountCrypto.constantEquals(state, query.get("state"))
                        && java.util.Set.of("completed", "cancelled").contains(query.getOrDefault("outcome", "")))
                    result = query.get("outcome");
            } catch (RuntimeException ignored) { /* Invalid callbacks cannot complete the operation. */ }
            byte[] body = LocalAccountService.callbackPage(result == null ? "This request could not be accepted. Return to Devboard to try again."
                    : "You can close this tab and continue in your terminal or Devboard.").getBytes(StandardCharsets.UTF_8);
            request.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            request.getResponseHeaders().set("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; script-src 'sha256-UxVXWyAt9U/Kult2HhuIFYy6Qb9b3amlNkkrXDu38Zg='; frame-ancestors 'none'");
            request.getResponseHeaders().set("Cache-Control", "no-store");
            request.getResponseHeaders().set("Referrer-Policy", "no-referrer");
            request.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            request.sendResponseHeaders(result == null ? 400 : 200, body.length);
            try (var output = request.getResponseBody()) {output.write(body);}
            if (result != null) outcome.complete(result);
        });
        server.start();
    }

    URI uri() {return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + PATH);}
    String await() throws Exception {
        try {return outcome.get(200, TimeUnit.MILLISECONDS);}
        catch (java.util.concurrent.TimeoutException ignored) {return null;}
    }
    @Override public void close() {server.stop(0);outcome.cancel(false);}
}
