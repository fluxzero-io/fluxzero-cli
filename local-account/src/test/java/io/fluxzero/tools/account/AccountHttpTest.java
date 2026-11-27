package io.fluxzero.tools.account;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AccountHttpTest {
    @Test void interruptionCancelsAnInFlightRequestAndPreservesTheInterrupt() throws Exception {
        var received = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var finished = new CountDownLatch(1);
        var failure = new AtomicReference<AccountException>();
        var interrupted = new AtomicBoolean();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/blocked", exchange -> {
            received.countDown();
            try { release.await(); }
            catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        try (var http = new AccountHttp()) {
            var endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/blocked");
            var worker = new Thread(() -> {
                try { http.get(endpoint); }
                catch (AccountException error) { failure.set(error); interrupted.set(Thread.currentThread().isInterrupted()); }
                finally { finished.countDown(); }
            }, "account-http-interruption-test");
            worker.setDaemon(true);
            worker.start();
            try {
                assertTrue(received.await(5, TimeUnit.SECONDS));
                worker.interrupt();
                assertTrue(finished.await(2, TimeUnit.SECONDS), "Cancellation must not wait for the network timeout");
                assertNotNull(failure.get());
                assertEquals("cancelled", failure.get().code());
                assertTrue(interrupted.get());
            } finally { release.countDown(); worker.interrupt(); worker.join(2000); }
        } finally { release.countDown(); server.stop(0); }
    }
}
