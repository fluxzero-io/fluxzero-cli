package io.fluxzero.tools.account;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.NativeLong;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.ByteByReference;
import com.sun.jna.ptr.PointerByReference;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Security.framework integration. Secrets cross the native boundary in memory, never in process arguments. */
final class MacKeychainStore implements SecureCredentialStore {
    private static final String SERVICE = "io.fluxzero.local-account.v1";
    private static final Object INTERACTION_LOCK = new Object();
    // Lazy loading keeps credential access and native library initialization out of local startup/status.
    private static final class Api {
        static final Security SECURITY = Native.load("Security", Security.class);
        static final CoreFoundation CF = Native.load("CoreFoundation", CoreFoundation.class);
        static final NativeLibrary SECURITY_LIBRARY = NativeLibrary.getInstance("Security");
        static final NativeLibrary CF_LIBRARY = NativeLibrary.getInstance("CoreFoundation");
    }
    interface Security extends Library {
        int SecItemCopyMatching(Pointer query, PointerByReference result);
        int SecItemAdd(Pointer attributes, PointerByReference result);
        int SecItemUpdate(Pointer query, Pointer attributes);
        int SecItemDelete(Pointer query);
        int SecKeychainGetUserInteractionAllowed(ByteByReference state);
        int SecKeychainSetUserInteractionAllowed(byte state);
    }
    interface CoreFoundation extends Library {
        Pointer CFStringCreateWithCString(Pointer allocator, String value, int encoding);
        Pointer CFDictionaryCreateMutable(Pointer allocator, NativeLong capacity, Pointer keys, Pointer values);
        void CFDictionarySetValue(Pointer dictionary, Pointer key, Pointer value);
        Pointer CFDataCreate(Pointer allocator, byte[] bytes, NativeLong length);
        NativeLong CFDataGetLength(Pointer data);
        Pointer CFDataGetBytePtr(Pointer data);
        void CFRelease(Pointer value);
    }

    public Optional<byte[]> read(String key, boolean interactive) {
        synchronized (INTERACTION_LOCK) {
            try (Interaction ignored = new Interaction(interactive); Query query = new Query(key, interactive)) {
                query.put("kSecReturnData", Api.CF_LIBRARY.getGlobalVariableAddress("kCFBooleanTrue").getPointer(0));
                var result = new PointerByReference();
                int status = Api.SECURITY.SecItemCopyMatching(query.dictionary, result);
                if (status == -25300) return Optional.empty();
                check(status);
                Pointer data = result.getValue();
                try {
                    long length = Api.CF.CFDataGetLength(data).longValue();
                    if (length < 0 || length > 65536) throw unavailable();
                    return Optional.of(Api.CF.CFDataGetBytePtr(data).getByteArray(0, (int) length));
                } finally { Api.CF.CFRelease(data); }
            } catch (UnsatisfiedLinkError | NoClassDefFoundError e) { throw unavailable(); }
        }
    }

    public void write(String key, byte[] value, boolean interactive) {
        if (value.length > 65536) throw unavailable();
        synchronized (INTERACTION_LOCK) {
            try (Interaction ignored = new Interaction(interactive); Query query = new Query(key, interactive); Query attributes = new Query()) {
                attributes.put("kSecValueData", attributes.owned(Api.CF.CFDataCreate(null, value, new NativeLong(value.length))));
                int status = Api.SECURITY.SecItemUpdate(query.dictionary, attributes.dictionary);
                if (status == -25300) {
                    query.put("kSecValueData", attributes.values.getLast());
                    query.put("kSecAttrAccessible", constant("kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly"));
                    status = Api.SECURITY.SecItemAdd(query.dictionary, null);
                }
                check(status);
            } catch (UnsatisfiedLinkError | NoClassDefFoundError e) { throw unavailable(); }
        }
    }

    public void delete(String key, boolean interactive) {
        synchronized (INTERACTION_LOCK) {
            try (Interaction ignored = new Interaction(interactive); Query query = new Query(key, interactive)) {
                int status = Api.SECURITY.SecItemDelete(query.dictionary);
                if (status != -25300) check(status);
            } catch (UnsatisfiedLinkError | NoClassDefFoundError e) { throw unavailable(); }
        }
    }

    /** The login keychain's application ACL prompts also require the process-wide interaction policy. */
    private static final class Interaction implements AutoCloseable {
        private final byte previous;
        Interaction(boolean interactive) {
            var state = new ByteByReference();
            check(Api.SECURITY.SecKeychainGetUserInteractionAllowed(state));
            previous = state.getValue();
            check(Api.SECURITY.SecKeychainSetUserInteractionAllowed((byte) (interactive ? 1 : 0)));
        }
        @Override public void close() {check(Api.SECURITY.SecKeychainSetUserInteractionAllowed(previous));}
    }

    private static Pointer constant(String name) { return Api.SECURITY_LIBRARY.getGlobalVariableAddress(name).getPointer(0); }
    private static AccountException unavailable() {
        return new AccountException("storage_unavailable", "Your secure account storage is unavailable. Try signing in again when it is accessible.");
    }
    private static void check(int status) {
        if (status == 0) return;
        if (status == -25308 || status == -25293 || status == -128)
            throw new AccountException("storage_locked", "Allow access to your secure account storage to continue signing in.");
        throw unavailable();
    }

    private static final class Query implements AutoCloseable {
        final List<Pointer> values = new ArrayList<>();
        final Pointer dictionary = Api.CF.CFDictionaryCreateMutable(null, new NativeLong(8), null, null);
        Query() {}
        Query(String key, boolean interactive) {
            put("kSecClass", constant("kSecClassGenericPassword"));
            put("kSecAttrService", string(SERVICE));
            put("kSecAttrAccount", string(key));
            put("kSecUseAuthenticationUI", constant(interactive ? "kSecUseAuthenticationUIAllow" : "kSecUseAuthenticationUIFail"));
        }
        Pointer owned(Pointer value) { values.add(value); return value; }
        Pointer string(String value) { return owned(Api.CF.CFStringCreateWithCString(null, value, 0x08000100)); }
        void put(String name, Pointer value) { Api.CF.CFDictionarySetValue(dictionary, constant(name), value); }
        public void close() {
            Api.CF.CFRelease(dictionary);
            values.forEach(Api.CF::CFRelease);
        }
    }
}
