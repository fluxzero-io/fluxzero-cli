package host.flux.cli.update

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.*

class CliUpdateProcessTest {
    @TempDir lateinit var directory: Path

    private fun child(mode: String): Process = ProcessBuilder(
        Path.of(System.getProperty("java.home"), "bin", if (isWindows()) "java.exe" else "java").toString(),
        "-cp", System.getProperty("java.class.path"), UpdateProcessFixture::class.java.name, mode, directory.toString()
    ).redirectErrorStream(true).start()

    @Test fun `a lock owned by another process fails fast and is released after termination`() {
        val worker = child("lock")
        try {
            assertEquals("locked", worker.inputStream.bufferedReader().readLine())
            val started = System.nanoTime()
            val failure = assertFails { CliUpdateSettings(directory).withInstallationLock { fail("must not install") } }
            assertContains(failure.message.orEmpty(), "Another CLI update is in progress")
            assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(2))
        } finally { worker.destroyForcibly(); assertTrue(worker.waitFor(10, TimeUnit.SECONDS)) }
        CliUpdateSettings(directory).withInstallationLock { Files.writeString(directory.resolve("acquired"), "ok") }
        assertTrue(Files.exists(directory.resolve("acquired")))
    }

    @Test fun `abrupt updater death between executable swaps leaves every command present`() {
        for (name in listOf("fz.exe", "fluxzero.exe")) Files.writeString(directory.resolve(name), "old executable")
        Files.writeString(directory.resolve("runtime.dll"), "unchanged runtime")
        Files.writeString(directory.resolve("candidate"), "new executable")
        val updater = child("replace-and-crash")
        assertTrue(updater.waitFor(10, TimeUnit.SECONDS))
        assertEquals(23, updater.exitValue(), updater.inputStream.bufferedReader().readText())
        assertEquals("new executable", Files.readString(directory.resolve("fz.exe")))
        assertEquals("old executable", Files.readString(directory.resolve("fluxzero.exe")))
        assertEquals("unchanged runtime", Files.readString(directory.resolve("runtime.dll")))
        replaceInstallation(mapOf(directory.resolve("fluxzero.exe") to directory.resolve("candidate")))
        assertEquals("new executable", Files.readString(directory.resolve("fluxzero.exe")))
    }
}

object UpdateProcessFixture {
    @JvmStatic fun main(args: Array<String>) {
        val directory = Path.of(args[1])
        when (args[0]) {
            "lock" -> CliUpdateSettings(directory).withInstallationLock {
                println("locked")
                System.out.flush()
                System.`in`.read()
            }
            "replace-and-crash" -> replaceInstallation(linkedMapOf(
                directory.resolve("fz.exe") to directory.resolve("candidate"),
                directory.resolve("fluxzero.exe") to directory.resolve("candidate"),
            )) { Runtime.getRuntime().halt(23) }
        }
    }
}
