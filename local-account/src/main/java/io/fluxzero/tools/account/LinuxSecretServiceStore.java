package io.fluxzero.tools.account;

import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.NativeLong;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.PointerByReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Session-bus Secret Service. Prompt objects are executed only during an explicit interactive action. */
final class LinuxSecretServiceStore implements SecureCredentialStore {
    private static final String BUS = "org.freedesktop.secrets", ROOT = "/org/freedesktop/secrets";
    private static final String SERVICE = "org.freedesktop.Secret.Service", COLLECTION = "org.freedesktop.Secret.Collection";
    private static final String ITEM = "org.freedesktop.Secret.Item", APPLICATION = "io.fluxzero.local-account.v1";
    private static final class Api {
        static final Secret SECRET = Native.load("secret-1", Secret.class);
        static final Glib GLIB = Native.load("glib-2.0", Glib.class);
        static final Gio GIO = Native.load("gio-2.0", Gio.class);
        static final GObject OBJECT = Native.load("gobject-2.0", GObject.class);
        static final ScheduledExecutorService DEADLINES = Executors.newSingleThreadScheduledExecutor(task -> {
            var thread = new Thread(task, "fluxzero-keyring-deadline"); thread.setDaemon(true); return thread;
        });
    }
    interface Secret extends Library {
        Pointer secret_service_get_sync(int flags, Pointer cancellable, PointerByReference error);
        Pointer secret_service_get_secret_for_dbus_path_sync(Pointer service, String path, Pointer cancellable, PointerByReference error);
        Pointer secret_service_encode_dbus_secret(Pointer service, Pointer value);
        Pointer secret_service_prompt_at_dbus_path_sync(Pointer service, String path, Pointer cancellable, Pointer type, PointerByReference error);
        Pointer secret_value_new(byte[] value, NativeLong size, String contentType);
        Pointer secret_value_get(Pointer value, Pointer size);
        void secret_value_unref(Pointer value);
    }
    interface Glib extends Library {
        Pointer g_variant_new_string(String value);
        Pointer g_variant_new_boolean(int value);
        Pointer g_variant_new_variant(Pointer value);
        Pointer g_variant_new_dict_entry(Pointer key, Pointer value);
        Pointer g_variant_new_array(Pointer type, Pointer[] values, NativeLong size);
        Pointer g_variant_new_tuple(Pointer[] values, NativeLong size);
        Pointer g_variant_type_new(String type);
        void g_variant_type_free(Pointer type);
        Pointer g_variant_get_child_value(Pointer value, NativeLong index);
        NativeLong g_variant_n_children(Pointer value);
        Pointer g_variant_get_string(Pointer value, Pointer length);
        Pointer g_variant_get_variant(Pointer value);
        int g_variant_get_boolean(Pointer value);
        void g_variant_unref(Pointer value);
        void g_error_free(Pointer error);
    }
    interface Gio extends Library {
        Pointer g_cancellable_new();
        void g_cancellable_cancel(Pointer value);
        Pointer g_dbus_proxy_get_connection(Pointer proxy);
        Pointer g_dbus_connection_call_sync(Pointer connection, String bus, String path, String iface, String method,
                Pointer parameters, Pointer replyType, int flags, int timeout, Pointer cancellable, PointerByReference error);
    }
    interface GObject extends Library { void g_object_unref(Pointer object); }

    public Optional<byte[]> read(String key, boolean interactive) {
        return access(interactive, session -> {
            List<String> paths = session.search(key);
            if (paths.isEmpty()) return Optional.empty();
            if (paths.size() != 1) throw unavailable();
            session.unlock(paths.get(0), ITEM);
            var error = new PointerByReference();
            Pointer secret = Api.SECRET.secret_service_get_secret_for_dbus_path_sync(session.service, paths.get(0), session.cancel, error);
            session.check(error);
            if (secret == null) throw unavailable();
            try (var length = new Memory(Native.SIZE_T_SIZE)) {
                Pointer data = Api.SECRET.secret_value_get(secret, length);
                long count = Native.SIZE_T_SIZE == 8 ? length.getLong(0) : Integer.toUnsignedLong(length.getInt(0));
                if (count < 0 || count > 65536 || count > 0 && data == null) throw unavailable();
                return Optional.of(count == 0 ? new byte[0] : data.getByteArray(0, (int) count));
            } finally { Api.SECRET.secret_value_unref(secret); }
        });
    }
    public void write(String key, byte[] value, boolean interactive) {
        if (value == null || value.length > 65536) throw unavailable();
        access(interactive, session -> {
            Pointer alias = session.call(ROOT, SERVICE, "ReadAlias", tuple(string("default")));
            String collection;
            try { collection = childString(alias, 0); } finally { unref(alias); }
            if (collection.equals("/")) {
                if (!interactive) throw locked();
                Pointer created = session.call(ROOT, SERVICE, "CreateCollection", tuple(properties(
                        entry("org.freedesktop.Secret.Collection.Label", string("Fluxzero Developer Tools"))), string("default")));
                try {
                    collection = childString(created, 0);
                    String prompt = childString(created, 1);
                    if (!prompt.equals("/")) {
                        Pointer result = session.prompt(prompt);
                        try { collection = text(result); } finally { unref(result); }
                    }
                } finally { unref(created); }
                if (collection.equals("/")) throw unavailable();
            }
            session.unlock(collection, COLLECTION);
            Pointer secret = Api.SECRET.secret_value_new(value, new NativeLong(value.length), "application/octet-stream");
            if (secret == null) throw unavailable();
            try {
                Pointer encoded = Api.SECRET.secret_service_encode_dbus_secret(session.service, secret);
                if (encoded == null) throw unavailable();
                Pointer result = session.call(collection, COLLECTION, "CreateItem", tuple(properties(
                        entry("org.freedesktop.Secret.Item.Label", string("Fluxzero Developer Tools")),
                        entry("org.freedesktop.Secret.Item.Attributes", attributes(key))), encoded, Api.GLIB.g_variant_new_boolean(1)));
                try { session.completePrompt(result, 1); } finally { unref(result); }
            } finally { Api.SECRET.secret_value_unref(secret); }
            return null;
        });
    }
    public void delete(String key, boolean interactive) {
        access(interactive, session -> {
            for (String path : session.search(key)) {
                Pointer result = session.call(path, ITEM, "Delete", null);
                try { session.completePrompt(result, 0); } finally { unref(result); }
            }
            return null;
        });
    }
    private interface Action<T> { T run(Session session); }
    private static <T> T access(boolean interactive, Action<T> action) {
        try (var session = new Session(interactive)) { return action.run(session); }
        catch (LinkageError error) { throw unavailable(); }
    }
    private static final class Session implements AutoCloseable {
        final Pointer cancel;
        final Pointer service;
        final boolean interactive;
        final ScheduledFuture<?> deadline;
        final Object cancellationLock = new Object();
        boolean closed;
        Session(boolean interactive) {
            this.interactive = interactive;
            cancel = Api.GIO.g_cancellable_new();
            if (cancel == null) throw unavailable();
            deadline = Api.DEADLINES.schedule(() -> {
                synchronized (cancellationLock) { if (!closed) Api.GIO.g_cancellable_cancel(cancel); }
            }, interactive ? 60 : 10, TimeUnit.SECONDS);
            Pointer service = null;
            try {
                var error = new PointerByReference();
                service = Api.SECRET.secret_service_get_sync(2, cancel, error); // Open an encrypted Secret Service session.
                check(error);
                if (service == null) throw unavailable();
                this.service = service;
            } catch (RuntimeException | Error error) {
                if (service != null) Api.OBJECT.g_object_unref(service);
                synchronized (cancellationLock) { closed = true; deadline.cancel(false); Api.OBJECT.g_object_unref(cancel); }
                throw error;
            }
        }
        Pointer call(String path, String iface, String method, Pointer parameters) {
            var error = new PointerByReference();
            Pointer result = Api.GIO.g_dbus_connection_call_sync(Api.GIO.g_dbus_proxy_get_connection(service), BUS,
                    path, iface, method, parameters, null, 0, 5000, cancel, error);
            check(error);
            if (result == null) throw unavailable();
            return result;
        }
        List<String> search(String key) {
            Pointer result = call(ROOT, SERVICE, "SearchItems", tuple(attributes(key)));
            try {
                var paths = new ArrayList<String>();
                for (int i = 0; i < 2; i++) {
                    Pointer array = child(result, i);
                    try {
                        long count = Api.GLIB.g_variant_n_children(array).longValue();
                        if (count > 64) throw unavailable();
                        for (int n = 0; n < count; n++) paths.add(childString(array, n));
                    } finally { unref(array); }
                }
                return paths;
            } finally { unref(result); }
        }
        void unlock(String path, String iface) {
            Pointer result = call(path, "org.freedesktop.DBus.Properties", "Get", tuple(string(iface), string("Locked")));
            boolean locked;
            try {
                Pointer outer = child(result, 0), value = Api.GLIB.g_variant_get_variant(outer);
                try { locked = Api.GLIB.g_variant_get_boolean(value) != 0; }
                finally { unref(value); unref(outer); }
            } finally { unref(result); }
            if (!locked) return;
            if (!interactive) throw locked();
            Pointer type = Api.GLIB.g_variant_type_new("o");
            Pointer item;
            try { // object paths require the correct GVariant type, not a string signature.
                item = Api.GLIB.g_variant_new_array(type, new Pointer[]{objectPath(path)}, new NativeLong(1));
            } finally { Api.GLIB.g_variant_type_free(type); }
            Pointer unlocked = call(ROOT, SERVICE, "Unlock", tuple(item));
            try { completePrompt(unlocked, 1); } finally { unref(unlocked); }
            Pointer checked = call(path, "org.freedesktop.DBus.Properties", "Get", tuple(string(iface), string("Locked")));
            try {
                Pointer outer = child(checked, 0), value = Api.GLIB.g_variant_get_variant(outer);
                try { if (Api.GLIB.g_variant_get_boolean(value) != 0) throw locked(); }
                finally { unref(value); unref(outer); }
            } finally { unref(checked); }
        }
        void completePrompt(Pointer result, int index) {
            String path = childString(result, index);
            if (!path.equals("/")) unref(prompt(path));
        }
        Pointer prompt(String path) {
            if (!interactive) throw locked();
            var error = new PointerByReference();
            Pointer result = Api.SECRET.secret_service_prompt_at_dbus_path_sync(service, path, cancel, null, error);
            check(error);
            if (result == null) throw locked();
            return result;
        }
        void check(PointerByReference error) {
            if (error.getValue() == null) return;
            Api.GLIB.g_error_free(error.getValue());
            throw unavailable(); // Never expose service error bodies or credentials.
        }
        public void close() {
            synchronized (cancellationLock) {
                closed = true; deadline.cancel(false); Api.OBJECT.g_object_unref(service); Api.OBJECT.g_object_unref(cancel);
            }
        }
    }
    private static Pointer string(String text) { return Api.GLIB.g_variant_new_string(text); }
    private static Pointer tuple(Pointer... children) { return Api.GLIB.g_variant_new_tuple(children, new NativeLong(children.length)); }
    private static Pointer child(Pointer value, int index) { return Api.GLIB.g_variant_get_child_value(value, new NativeLong(index)); }
    private static String text(Pointer value) { return Api.GLIB.g_variant_get_string(value, null).getString(0, "UTF-8"); }
    private static String childString(Pointer value, int index) { Pointer part = child(value, index); try { return text(part); } finally { unref(part); } }
    private static void unref(Pointer value) { if (value != null) Api.GLIB.g_variant_unref(value); }
    private static Pointer entry(String key, Pointer value) { return Api.GLIB.g_variant_new_dict_entry(string(key), Api.GLIB.g_variant_new_variant(value)); }
    private static Pointer properties(Pointer... entries) { return array("{sv}", entries); }
    private static Pointer array(String type, Pointer... entries) {
        Pointer nativeType = Api.GLIB.g_variant_type_new(type);
        try { return Api.GLIB.g_variant_new_array(nativeType, entries, new NativeLong(entries.length)); }
        finally { Api.GLIB.g_variant_type_free(nativeType); }
    }
    private static Pointer attributes(String key) {
        if (key == null || !key.matches("[a-zA-Z0-9_-]{1,128}")) throw unavailable();
        return array("{ss}", Api.GLIB.g_variant_new_dict_entry(string("application"), string(APPLICATION)),
                Api.GLIB.g_variant_new_dict_entry(string("account"), string(key)));
    }
    private static Pointer objectPath(String path) { return ObjectPaths.API.g_variant_new_object_path(path); }
    interface ObjectPathApi extends Library { Pointer g_variant_new_object_path(String path); }
    private static final class ObjectPaths { static final ObjectPathApi API = Native.load("glib-2.0", ObjectPathApi.class); }
    private static AccountException unavailable() { return new AccountException("storage_unavailable",
            "Linux secure storage is unavailable. Start your desktop Secret Service and install libsecret, then try again. Local development remains available."); }
    private static AccountException locked() { return new AccountException("storage_locked",
            "Unlock your desktop keyring to continue signing in."); }
}
