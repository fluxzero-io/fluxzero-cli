package io.fluxzero.tools.account;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BrowserLauncherTest {
    @Test void aLinuxOpenerMayRemainAttachedToTheBrowser() throws Exception {
        var process = new Opener(false, 0);
        BrowserLauncher.Processes.awaitOpening(process, true);
        assertFalse(process.destroyed, "A browser-owned process must remain running for the login callback");
    }

    @Test void aLauncherThatAlreadyFailedIsReported() {
        assertThrows(IllegalStateException.class, () -> BrowserLauncher.Processes.awaitOpening(new Opener(true, 4), true));
    }

    @Test void aHungMacOpenerIsStopped() {
        var process = new Opener(false, 0);
        assertThrows(IllegalStateException.class, () -> BrowserLauncher.Processes.awaitOpening(process, false));
        assertTrue(process.destroyed);
    }

    private static final class Opener extends Process {
        final boolean exited;
        final int code;
        boolean destroyed;
        Opener(boolean exited, int code) { this.exited = exited; this.code = code; }
        public OutputStream getOutputStream() { return OutputStream.nullOutputStream(); }
        public InputStream getInputStream() { return InputStream.nullInputStream(); }
        public InputStream getErrorStream() { return InputStream.nullInputStream(); }
        public int waitFor() { return code; }
        public boolean waitFor(long timeout, TimeUnit unit) { return exited; }
        public int exitValue() { if (!exited) throw new IllegalThreadStateException(); return code; }
        public void destroy() { destroyed = true; }
        public Process destroyForcibly() { destroyed = true; return this; }
    }
}
