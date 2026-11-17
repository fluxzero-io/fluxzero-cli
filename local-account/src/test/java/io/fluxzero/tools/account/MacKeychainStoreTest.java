package io.fluxzero.tools.account;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@EnabledOnOs(OS.MAC)
@EnabledIfEnvironmentVariable(named = "FLUXZERO_TEST_KEYCHAIN", matches = "true")
class MacKeychainStoreTest {
    @Test void storesUpdatesReadsAndDeletesOnlyItsOwnSyntheticItemWithoutBackgroundDialogs() {
        var store = new MacKeychainStore();
        String key = "synthetic-test-" + UUID.randomUUID();
        try {
            assertTrue(store.read(key, false).isEmpty());
            store.write(key, "synthetic-account-test".getBytes(StandardCharsets.UTF_8), true);
            assertArrayEquals("synthetic-account-test".getBytes(StandardCharsets.UTF_8), store.read(key, false).orElseThrow());
            store.write(key, "updated-synthetic-test".getBytes(StandardCharsets.UTF_8), true);
            assertArrayEquals("updated-synthetic-test".getBytes(StandardCharsets.UTF_8), store.read(key, false).orElseThrow());
        } finally { store.delete(key, false); }
        assertTrue(store.read(key, false).isEmpty());
    }
}
