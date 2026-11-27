package io.fluxzero.tools.account;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.WString;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

/** Generic Windows credentials. A small atomic manifest permits sessions larger than one 2560-byte blob. */
final class WindowsCredentialStore implements SecureCredentialStore {
    private static final int CHUNK = 2560, MAXIMUM = 65536, MAGIC = 0x465a4131;
    private static final String PREFIX = "io.fluxzero.local-account.v1/";
    interface Credentials {
        Optional<byte[]> read(String target);
        void write(String target, byte[] value);
        void delete(String target);
    }
    private final Credentials credentials;
    WindowsCredentialStore() { this(new NativeCredentials()); }
    WindowsCredentialStore(Credentials credentials) { this.credentials = credentials; }
    private static String target(String key) {
        if (key == null || !key.matches("[a-zA-Z0-9_-]{1,128}")) throw unavailable();
        return PREFIX + key;
    }
    private record Manifest(UUID generation, int length) {
        String chunk(String target, int index) { return target + "/" + generation + "/" + index; }
        int chunks() { return Math.max(1, (length + CHUNK - 1) / CHUNK); }
        byte[] bytes() { return ByteBuffer.allocate(24).putInt(MAGIC).putLong(generation.getMostSignificantBits())
                .putLong(generation.getLeastSignificantBits()).putInt(length).array(); }
        static Manifest parse(byte[] value) {
            if (value.length != 24) throw unavailable();
            var buffer = ByteBuffer.wrap(value);
            if (buffer.getInt() != MAGIC) throw unavailable();
            var result = new Manifest(new UUID(buffer.getLong(), buffer.getLong()), buffer.getInt());
            if (result.length < 0 || result.length > MAXIMUM) throw unavailable();
            return result;
        }
    }
    public Optional<byte[]> read(String key, boolean interactive) {
        String target = target(key);
        var root = credentials.read(target);
        if (root.isEmpty()) return Optional.empty();
        var manifest = Manifest.parse(root.get());
        byte[] value = new byte[manifest.length];
        for (int i = 0; i < manifest.chunks(); i++) {
            byte[] chunk = credentials.read(manifest.chunk(target, i)).orElseThrow(WindowsCredentialStore::unavailable);
            try {
                int expected = Math.min(CHUNK, value.length - i * CHUNK);
                if (chunk.length != expected) { Arrays.fill(value, (byte) 0); throw unavailable(); }
                System.arraycopy(chunk, 0, value, i * CHUNK, expected);
            } finally { Arrays.fill(chunk, (byte) 0); }
        }
        return Optional.of(value);
    }
    public void write(String key, byte[] value, boolean interactive) {
        if (value == null || value.length > MAXIMUM) throw unavailable();
        String target = target(key);
        var previous = credentials.read(target).map(Manifest::parse);
        var next = new Manifest(UUID.randomUUID(), value.length);
        try {
            for (int i = 0; i < next.chunks(); i++) {
                byte[] chunk = Arrays.copyOfRange(value, i * CHUNK, Math.min(value.length, (i + 1) * CHUNK));
                try { credentials.write(next.chunk(target, i), chunk); }
                finally { Arrays.fill(chunk, (byte) 0); }
            }
            credentials.write(target, next.bytes());
        } catch (RuntimeException error) {
            // Never delete possibly committed chunks after an uncertain manifest write.
            var current = credentials.read(target).map(Manifest::parse);
            if (current.isEmpty() || !current.get().generation.equals(next.generation)) cleanup(target, next);
            throw error;
        }
        previous.ifPresent(old -> cleanup(target, old));
    }
    public void delete(String key, boolean interactive) {
        String target = target(key);
        var previous = credentials.read(target).map(Manifest::parse);
        credentials.delete(target);
        previous.ifPresent(old -> cleanup(target, old));
    }
    private void cleanup(String target, Manifest manifest) {
        for (int i = 0; i < manifest.chunks(); i++) {
            try { credentials.delete(manifest.chunk(target, i)); }
            catch (AccountException ignored) { /* Only unreachable OS-protected chunks can remain. */ }
        }
    }
    static AccountException unavailable() {
        return new AccountException("storage_unavailable", "Windows Credential Manager is unavailable. Sign in again when your Windows session is accessible.");
    }
    interface Advapi extends StdCallLibrary {
        int CredReadW(WString target, int type, int flags, PointerByReference credential);
        int CredWriteW(Credential credential, int flags);
        int CredDeleteW(WString target, int type, int flags);
        void CredFree(Pointer credential);
    }
    private static final class Api { static final Advapi API = Native.load("Advapi32", Advapi.class); }
    @Structure.FieldOrder({"flags", "type", "target", "comment", "writtenLow", "writtenHigh", "size", "blob",
            "persist", "attributeCount", "attributes", "alias", "username"})
    public static class Credential extends Structure {
        public int flags, type;
        public WString target, comment;
        public int writtenLow, writtenHigh, size;
        public Pointer blob;
        public int persist, attributeCount;
        public Pointer attributes;
        public WString alias, username;
        public Credential() {}
        public Credential(Pointer pointer) { super(pointer); read(); }
    }
    private static final class NativeCredentials implements Credentials {
        public Optional<byte[]> read(String target) {
            try {
                var result = new PointerByReference();
                if (Api.API.CredReadW(new WString(target), 1, 0, result) == 0) {
                    if (Native.getLastError() == 1168) return Optional.empty();
                    throw unavailable();
                }
                try {
                    var credential = new Credential(result.getValue());
                    if (credential.size < 0 || credential.size > CHUNK || credential.size > 0 && credential.blob == null) throw unavailable();
                    return Optional.of(credential.size == 0 ? new byte[0] : credential.blob.getByteArray(0, credential.size));
                } finally { Api.API.CredFree(result.getValue()); }
            } catch (LinkageError error) { throw unavailable(); }
        }
        public void write(String target, byte[] value) {
            try (var data = new Memory(Math.max(1, value.length))) {
                data.write(0, value, 0, value.length);
                var credential = new Credential();
                credential.type = 1; credential.target = new WString(target); credential.size = value.length;
                credential.blob = data; credential.persist = 2; credential.username = new WString("Fluxzero Developer Tools");
                try { if (Api.API.CredWriteW(credential, 0) == 0) throw unavailable(); }
                finally { data.clear(); }
            } catch (LinkageError error) { throw unavailable(); }
        }
        public void delete(String target) {
            try { if (Api.API.CredDeleteW(new WString(target), 1, 0) == 0 && Native.getLastError() != 1168) throw unavailable(); }
            catch (LinkageError error) { throw unavailable(); }
        }
    }
}
