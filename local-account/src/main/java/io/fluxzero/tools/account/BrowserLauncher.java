package io.fluxzero.tools.account;

import java.net.URI;
import java.util.concurrent.TimeUnit;

/** Opens the user's browser only after an explicit sign-in action. */
@FunctionalInterface
public interface BrowserLauncher {
    void open(URI uri);

    interface Shell extends com.sun.jna.win32.StdCallLibrary {
        com.sun.jna.Pointer ShellExecuteW(com.sun.jna.Pointer window, com.sun.jna.WString operation,
                com.sun.jna.WString file, com.sun.jna.WString parameters, com.sun.jna.WString directory, int show);
    }
    final class Windows {
        private Windows() {}
        static final Shell API = com.sun.jna.Native.load("shell32", Shell.class);
    }

    static BrowserLauncher system() {
        return uri -> {
            AccountConfiguration.requireTrustedTransport(uri);
            String os = System.getProperty("os.name", "");
            try {
                if (os.startsWith("Windows")) {
                    long result = com.sun.jna.Pointer.nativeValue(Windows.API.ShellExecuteW(null, new com.sun.jna.WString("open"),
                            new com.sun.jna.WString(uri.toString()), null, null, 1));
                    if (result <= 32) throw new IllegalStateException();
                    return;
                }
                String opener = os.startsWith("Mac") ? "/usr/bin/open" : os.equals("Linux") ? "xdg-open" : null;
                if (opener == null) throw new IllegalStateException();
                Process process = new ProcessBuilder(opener, uri.toString()).redirectError(ProcessBuilder.Redirect.DISCARD)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
                if (!process.waitFor(3, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new IllegalStateException(); }
                if (process.exitValue() != 0) throw new IllegalStateException();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AccountException("cancelled", "Sign-in was cancelled. You can continue working locally.");
            } catch (Exception | LinkageError e) {
                throw new AccountException("browser_unavailable", "Your browser could not be opened. Try signing in again from this computer.");
            }
        };
    }
}
