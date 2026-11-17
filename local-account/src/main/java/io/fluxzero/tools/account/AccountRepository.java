package io.fluxzero.tools.account;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Function;

/** Non-secret account projection and bounded cross-process coordination, independent from project state. */
final class AccountRepository {
    record State(int version, long generation, String credentialKey, String subject, String name, String email,
                 long expiresAt, boolean renewable, String operationId, long loginDeadline, String message) {
        static State empty() { return new State(1, 0, null, null, null, null, 0, false, null, 0, null); }
        State signedOut(String message) { return new State(1, generation + 1, null, null, null, null, 0, false, null, 0, message); }
    }

    record BrowserAction(int version, String id, String kind, long generation, String credentialKey, long expiresAt) {}

    BrowserAction browserAction(State state) {
        Path file = directory.resolve("browser-action.json");
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 4096) return null;
            var action = AccountHttp.JSON.readValue(Files.readAllBytes(file), BrowserAction.class);
            return action.version() == 1 && "sign_out_everywhere".equals(action.kind())
                    && action.generation() == state.generation() && java.util.Objects.equals(action.credentialKey(), state.credentialKey())
                    && state.credentialKey() != null && action.expiresAt() > Instant.now().getEpochSecond() ? action : null;
        } catch (Exception ignored) { return null; }
    }

    void writeBrowserAction(BrowserAction action) {
        Path file = directory.resolve("browser-action.json");
        if (action == null) {try {Files.deleteIfExists(file);} catch (java.io.IOException e) {throw unavailable();} return;}
        Path temporary = directory.resolve("action-" + UUID.randomUUID() + ".tmp");
        try {
            Files.write(temporary, AccountHttp.JSON.writeValueAsBytes(action), StandardOpenOption.CREATE_NEW);
            permissions(temporary, "rw-------");
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) { throw unavailable(); }
        finally {try {Files.deleteIfExists(temporary);} catch (java.io.IOException ignored) {}}
    }

    private final AccountConfiguration configuration;
    private final Path directory;
    AccountRepository(AccountConfiguration configuration) {
        this.configuration = configuration;
        directory = configuration.directory().resolve(configuration.storageId());
    }

    State read() {
        Path file = directory.resolve("state.json");
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return State.empty();
        try {
            if (Files.isSymbolicLink(file) || Files.size(file) > 16384) throw new IllegalStateException();
            State state = AccountHttp.JSON.readValue(Files.readAllBytes(file), State.class);
            if (state.version != 1 || state.generation < 0) throw new IllegalStateException();
            return state;
        } catch (Exception e) { throw unavailable(); }
    }

    AccountStatus status() { return status(read()); }
    AccountStatus status(State state) {
        long now = Instant.now().getEpochSecond();
        String status = state.operationId != null && state.loginDeadline > now ? "signing_in"
                : state.credentialKey == null ? "signed_out"
                : state.expiresAt > now || state.renewable ? "signed_in" : "sign_in_required";
        return new AccountStatus(status, state.name, state.email, configuration.accountUrl().toString(),
                configuration.environment(), state.expiresAt, state.renewable,
                "signing_in".equals(status) ? state.operationId : null, state.message,
                configuration.dashboardUrl().toString(), configuration.sessionsUrl().toString(),
                browserAction(state) == null ? null : "sign_out_everywhere");
    }

    <T> T locked(String name, Function<State, T> action) {
        prepareDirectory();
        Path path = directory.resolve(name + ".lock");
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            permissions(path, "rw-------");
            FileLock lock;
            try { lock = channel.tryLock(); } catch (OverlappingFileLockException e) { lock = null; }
            if (lock == null) throw new AccountException("busy", "Another account action is finishing. Try again shortly.");
            try (FileLock ignored = lock) { return action.apply(read()); }
        } catch (AccountException e) { throw e; }
        catch (java.io.IOException e) { throw unavailable(); }
    }

    void write(State state) {
        Path temporary = directory.resolve("state-" + UUID.randomUUID() + ".tmp");
        try {
            Files.write(temporary, AccountHttp.JSON.writeValueAsBytes(state), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            permissions(temporary, "rw-------");
            Files.move(temporary, directory.resolve("state.json"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) { throw unavailable(); }
        finally { try { Files.deleteIfExists(temporary); } catch (java.io.IOException ignored) {} }
    }

    void recordFailure(String code) {
        // Fixed application codes only. Never persist provider bodies, URLs, codes or credentials.
        try {
            prepareDirectory();
            Path file = directory.resolve("diagnostics.json");
            if (Files.isSymbolicLink(file)) return;
            Files.write(file, AccountHttp.JSON.writeValueAsBytes(java.util.Map.of(
                    "version", 1, "code", code, "failedAt", Instant.now().getEpochSecond())),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS);
            permissions(file, "rw-------");
        } catch (Exception ignored) { /* Diagnostics must not interfere with account or local work. */ }
    }

    private void prepareDirectory() {
        try {
            if (Files.isSymbolicLink(configuration.directory()) || Files.isSymbolicLink(directory)) throw unavailable();
            Files.createDirectories(directory);
            permissions(configuration.directory(), "rwx------");
            permissions(directory, "rwx------");
        } catch (java.io.IOException e) { throw unavailable(); }
    }
    private static void permissions(Path path, String permissions) throws java.io.IOException {
        if (path.getFileSystem().supportedFileAttributeViews().contains("posix"))
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(permissions));
    }
    private static AccountException unavailable() {
        return new AccountException("state_unavailable", "Your local account information cannot be read. Local development is still available.");
    }
}
