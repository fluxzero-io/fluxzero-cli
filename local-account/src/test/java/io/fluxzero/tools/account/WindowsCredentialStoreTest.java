package io.fluxzero.tools.account;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

class WindowsCredentialStoreTest {
    static final class Backend implements WindowsCredentialStore.Credentials {
        final Map<String, byte[]> items = new HashMap<>();
        String failedTarget;
        boolean lostManifestReply;
        public Optional<byte[]> read(String key) { return Optional.ofNullable(items.get(key)).map(byte[]::clone); }
        public void write(String key, byte[] value) {
            if (key.equals(failedTarget)) throw WindowsCredentialStore.unavailable();
            items.put(key, value.clone());
            if (lostManifestReply && key.split("/").length == 2) { lostManifestReply = false; throw WindowsCredentialStore.unavailable(); }
        }
        public void delete(String key) { items.remove(key); }
    }
    @Test void preservesLargeSessionsAcrossAtomicUpdatesAndDeletesAllReachableChunks() {
        var backend = new Backend(); var store = new WindowsCredentialStore(backend);
        assertTrue(store.read("synthetic", false).isEmpty());
        byte[] value = new byte[65536]; Arrays.fill(value, (byte) 71);
        store.write("synthetic", value, true);
        assertArrayEquals(value, store.read("synthetic", false).orElseThrow());
        assertTrue(backend.items.values().stream().allMatch(v -> v.length <= 2560));
        store.write("synthetic", new byte[]{1, 2, 3}, false);
        assertEquals(2, backend.items.size());
        assertArrayEquals(new byte[]{1, 2, 3}, store.read("synthetic", false).orElseThrow());
        store.delete("synthetic", false);
        assertTrue(store.read("synthetic", false).isEmpty()); assertTrue(backend.items.isEmpty());
    }
    @Test void failedManifestUpdatePreservesPreviousSessionAndRemovesUncommittedChunks() {
        var backend = new Backend(); var store = new WindowsCredentialStore(backend);
        store.write("synthetic", new byte[]{7}, true);
        backend.failedTarget = "io.fluxzero.local-account.v1/synthetic";
        assertThrows(AccountException.class, () -> store.write("synthetic", new byte[8192], true));
        assertArrayEquals(new byte[]{7}, store.read("synthetic", false).orElseThrow());
        assertEquals(2, backend.items.size());
    }
    @Test void lostManifestReplyDoesNotDeleteTheCommittedSession() {
        var backend = new Backend(); var store = new WindowsCredentialStore(backend);
        backend.lostManifestReply = true;
        assertThrows(AccountException.class, () -> store.write("synthetic", new byte[8192], true));
        assertArrayEquals(new byte[8192], store.read("synthetic", false).orElseThrow());
        store.delete("synthetic", false); assertTrue(backend.items.isEmpty());
    }
    @Test void corruptedManifestMissingChunksAndOversizedWritesFailClosed() {
        var backend = new Backend(); var store = new WindowsCredentialStore(backend);
        assertThrows(AccountException.class, () -> store.write("synthetic", new byte[65537], true));
        store.write("synthetic", new byte[8192], true);
        backend.items.keySet().removeIf(k -> k.endsWith("/1"));
        assertThrows(AccountException.class, () -> store.read("synthetic", false));
        backend.items.put("io.fluxzero.local-account.v1/synthetic", new byte[24]);
        assertThrows(AccountException.class, () -> store.read("synthetic", false));
        assertThrows(AccountException.class, () -> store.read("foreign/target", false));
    }
}
