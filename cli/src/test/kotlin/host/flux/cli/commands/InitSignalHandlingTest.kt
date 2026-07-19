package host.flux.cli.commands

import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import host.flux.cli.FluxCli
import host.flux.dev.DevLauncher
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@EnabledOnOs(OS.LINUX, OS.MAC)
class InitSignalHandlingTest {
    @Test
    fun `cli command graph leaves dev sigint handling intact`() {
        val output = ByteArrayOutputStream()
        val process = ProcessBuilder(
            javaExecutable(), "-cp", System.getProperty("java.class.path"), InitSignalFixture::class.java.name
        ).redirectErrorStream(true).start()
        val reader = thread(isDaemon = true, name = "init-signal-test-output") {
            process.inputStream.copyTo(output)
        }
        try {
            assertTrue(awaitOutput(output, "ready"), output.toString(Charsets.UTF_8))

            val signal = ProcessBuilder("kill", "-INT", process.pid().toString()).start()
            assertTrue(signal.waitFor(2, TimeUnit.SECONDS) && signal.exitValue() == 0)
            assertTrue(process.waitFor(4, TimeUnit.SECONDS), "SIGINT was intercepted by the unused init prompt")
            reader.join(1_000)

            val text = output.toString(Charsets.UTF_8)
            assertEquals(1, Regex("Fluxzero dev server stopped\\.").findAll(text).count(), text)
            assertEquals(130, process.exitValue())
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    private fun awaitOutput(output: ByteArrayOutputStream, expected: String): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            if (output.toString(Charsets.UTF_8).contains(expected)) return true
            Thread.sleep(25)
        }
        return false
    }

    private fun javaExecutable(): String = Path.of(
        System.getProperty("java.home"), "bin",
        if (System.getProperty("os.name").lowercase().contains("win")) "java.exe" else "java"
    ).toString()
}

object InitSignalFixture {
    @JvmStatic
    fun main(args: Array<String>) {
        val devLauncher = DevLauncher {
            Runtime.getRuntime().addShutdownHook(Thread { println("Fluxzero dev server stopped.") })
            println("ready")
            System.out.flush()
            CountDownLatch(1).await()
            0
        }
        FluxCli().subcommands(Init(), Dev(devLauncher)).main(arrayOf("dev"))
    }
}
