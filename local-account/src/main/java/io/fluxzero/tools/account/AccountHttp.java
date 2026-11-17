package io.fluxzero.tools.account;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/** Bounded, non-redirecting protocol transport. Response bodies never become exception messages. */
final class AccountHttp implements AutoCloseable {
    static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    record Reply(int status, JsonNode body, String nonce) {}

    Reply get(URI uri) { return send(HttpRequest.newBuilder(uri).GET().build()); }

    Reply post(URI uri, Map<String, String> fields, String proof) {
        var builder = HttpRequest.newBuilder(uri).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form(fields)));
        if (proof != null) builder.header("DPoP", proof);
        return send(builder.build());
    }

    private Reply send(HttpRequest request) {
        var future = client.sendAsync(request, ignored -> new LimitedBody());
        try {
            var response = future.thenApply(value -> value).orTimeout(10, TimeUnit.SECONDS).join();
            byte[] bytes = response.body();
            JsonNode body;
            try { body = bytes.length == 0 ? JSON.createObjectNode() : JSON.readTree(bytes); }
            catch (Exception e) { throw new AccountException("service_unavailable", "The account service could not complete this request. Try again shortly."); }
            return new Reply(response.statusCode(), body, response.headers().firstValue("DPoP-Nonce").orElse(null));
        } catch (AccountException e) { throw e; }
        catch (Exception e) {
            future.cancel(true);
            throw new AccountException("offline", "Fluxzero account services cannot be reached. You can keep working locally and try again later.");
        }
    }

    static String form(Map<String, String> values) {
        return values.entrySet().stream().map(e -> encode(e.getKey()) + "=" + encode(e.getValue())).collect(Collectors.joining("&"));
    }
    static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }

    @Override public void close() { client.shutdownNow(); }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
        private Flow.Subscription subscription;
        private int received;
        public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
        public void onSubscribe(Flow.Subscription value) { subscription = value; delegate.onSubscribe(value); }
        public void onNext(List<ByteBuffer> chunks) {
            for (var chunk : chunks) received = Math.addExact(received, chunk.remaining());
            if (received > 131072) {
                subscription.cancel();
                delegate.onError(new IllegalArgumentException("Account response exceeds its size limit"));
            } else delegate.onNext(chunks);
        }
        public void onError(Throwable error) { delegate.onError(error); }
        public void onComplete() { delegate.onComplete(); }
    }
}
