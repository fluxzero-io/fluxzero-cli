package io.fluxzero.tools.account;

import java.util.Optional;

/** OS credential integration. Noninteractive calls must never display an OS authentication dialog. */
public interface SecureCredentialStore {
    Optional<byte[]> read(String key, boolean interactive);
    void write(String key, byte[] value, boolean interactive);
    void delete(String key, boolean interactive);

    static SecureCredentialStore system() {
        String os = System.getProperty("os.name", "");
        if (os.startsWith("Mac")) return new MacKeychainStore();
        if (os.startsWith("Windows")) return new WindowsCredentialStore();
        if (os.equals("Linux")) return new LinuxSecretServiceStore();
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
