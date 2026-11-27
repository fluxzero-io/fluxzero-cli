package io.fluxzero.tools.account;

import org.junit.jupiter.api.Test;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import static org.junit.jupiter.api.Assertions.*;

class AccountLogoutCallbackTest {
    @Test void acceptsOnlyTheCorrelatedOutcomeAtTheExactLoopbackPath() throws Exception {
        try (var callback = new AccountLogoutCallback("expected")) {
            var http = HttpClient.newHttpClient();
            for (String query : new String[]{"?state=wrong&outcome=completed", "?state=expected&outcome=unknown", "?state=expected&state=wrong&outcome=cancelled"})
                assertEquals(400, http.send(HttpRequest.newBuilder(URI.create(callback.uri()+query)).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode());
            var response = http.send(HttpRequest.newBuilder(URI.create(callback.uri()+"?state=expected&outcome=cancelled")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200,response.statusCode());
            assertEquals("cancelled",callback.await());
            assertFalse(response.body().contains("expected"));
            assertEquals("no-store",response.headers().firstValue("Cache-Control").orElseThrow());
        }
    }
}
