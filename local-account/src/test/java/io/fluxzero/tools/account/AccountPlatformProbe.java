package io.fluxzero.tools.account;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;

/** Test-only entry point, built separately to qualify the same OS adapters in JVM and native images. */
public final class AccountPlatformProbe {
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("Expected operation, synthetic key and temporary directory");
        String operation = args[0], key = args[1];
        if (!key.startsWith("synthetic-")) throw new IllegalArgumentException("Synthetic keys only");
        var store = SecureCredentialStore.system();
        byte[] expected = new byte[8192]; Arrays.fill(expected, (byte) (operation.equals("updated") ? 72 : 71));
        switch (operation) {
            case "write" -> store.write(key, expected, true);
            case "read", "updated" -> {
                if (!Arrays.equals(expected, store.read(key, false).orElseThrow())) throw new AssertionError("OS credential roundtrip failed");
            }
            case "update" -> { Arrays.fill(expected, (byte) 72); store.write(key, expected, false); }
            case "delete" -> { store.delete(key, false); if (store.read(key, false).isPresent()) throw new AssertionError("OS credential remains"); }
            case "locked" -> {
                try { store.read(key, false); throw new AssertionError("Locked service must reject background access"); }
                catch (AccountException e) { if (!e.code().equals("storage_locked")) throw e; }
            }
            default -> throw new IllegalArgumentException("Unknown synthetic operation");
        }
        Path metadata = Path.of(args[2]);
        var configuration = new AccountConfiguration("platform-probe", URI.create("https://identity.example.test"),
                "native-client", "", URI.create("https://identity.example.test/account"), metadata, true, Duration.ofSeconds(10));
        try (var account = new LocalAccountService(configuration, store, ignored -> { throw new AssertionError("No browser expected"); })) {
            if (!account.status().state().equals("signed_out")) throw new AssertionError("Unexpected status");
            if (Files.exists(metadata)) throw new AssertionError("Passive status created account files");
        }
        var repository = new AccountRepository(configuration);
        repository.locked("state", state -> { repository.write(AccountRepository.State.empty()); return null; });
        if (!repository.status().state().equals("signed_out")) throw new AssertionError("Metadata roundtrip failed");
        try (var paths = Files.walk(metadata)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
        }
        System.out.println("Synthetic OS credential and private metadata check passed: " + operation);
    }
}
