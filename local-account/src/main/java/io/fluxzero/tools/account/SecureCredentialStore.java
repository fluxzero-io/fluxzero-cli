package io.fluxzero.tools.account;

import java.util.Optional;

/** OS credential integration. Noninteractive calls must never display an OS authentication dialog. */
public interface SecureCredentialStore {
    Optional<byte[]> read(String key, boolean interactive);
    void write(String key, byte[] value, boolean interactive);
    void delete(String key, boolean interactive);

    static SecureCredentialStore system() {
        if (System.getProperty("os.name", "").startsWith("Mac")) return new MacKeychainStore();
        return new SecureCredentialStore() {
            private AccountException unavailable() {
                return new AccountException("storage_unavailable", "Secure sign-in is not available on this system yet. You can keep working locally.");
            }
            public Optional<byte[]> read(String key, boolean interactive) { throw unavailable(); }
            public void write(String key, byte[] value, boolean interactive) { throw unavailable(); }
            public void delete(String key, boolean interactive) { throw unavailable(); }
        };
    }
}
