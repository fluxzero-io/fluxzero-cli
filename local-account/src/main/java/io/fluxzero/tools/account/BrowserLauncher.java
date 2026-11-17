package io.fluxzero.tools.account;

import java.net.URI;
import java.util.concurrent.TimeUnit;

/** Opens the user's browser only after an explicit sign-in action. */
@FunctionalInterface
public interface BrowserLauncher {
    void open(URI uri);

    static BrowserLauncher system() {
        return uri -> {
            AccountConfiguration.requireTrustedTransport(uri);
            String os = System.getProperty("os.name", "");
            if (!os.startsWith("Mac")) throw new AccountException("browser_unavailable", "Browser sign-in is not available on this system yet. You can keep working locally.");
            try {
                Process process = new ProcessBuilder("/usr/bin/open", uri.toString()).redirectError(ProcessBuilder.Redirect.DISCARD)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
                if (!process.waitFor(3, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new IllegalStateException(); }
                if (process.exitValue() != 0) throw new IllegalStateException();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AccountException("cancelled", "Sign-in was cancelled. You can continue working locally.");
            } catch (Exception e) {
                throw new AccountException("browser_unavailable", "Your browser could not be opened. Try signing in again from this computer.");
            }
        };
    }
}
