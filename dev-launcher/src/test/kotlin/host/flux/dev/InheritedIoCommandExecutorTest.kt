package host.flux.dev

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@EnabledOnOs(OS.LINUX, OS.MAC)
class InheritedIoCommandExecutorTest {
    @Test
    fun `launcher reports shutdown only after its child has stopped`() {
        val output = ByteArrayOutputStream()
        val process = ProcessBuilder(
            javaExecutable(), "-cp", fixtureClasspath(), ExecutorParentFixture::class.java.name
        ).redirectErrorStream(true).start()
        val reader = thread(isDaemon = true, name = "launcher-shutdown-test-output") {
            process.inputStream.copyTo(output)
        }
        try {
            assertTrue(awaitOutput(output, "child ready"), output.toString(Charsets.UTF_8))

            val signal = ProcessBuilder("kill", "-TERM", process.pid().toString()).start()
            assertTrue(signal.waitFor(2, TimeUnit.SECONDS) && signal.exitValue() == 0)
            assertTrue(process.waitFor(4, TimeUnit.SECONDS), "launcher did not stop after one signal")
            reader.join(1_000)

            val text = output.toString(Charsets.UTF_8)
            val childStopped = text.indexOf("child stopped")
            val launcherStopped = text.indexOf("Fluxzero dev stopped.")
            assertTrue(childStopped >= 0 && launcherStopped > childStopped, text)
            assertTrue(text.trimEnd().endsWith("Fluxzero dev stopped."), text)
            assertEquals(143, process.exitValue())
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    @Test
    fun `launcher reports shutdown while transitioning between children`() {
        val output = ByteArrayOutputStream()
        val process = ProcessBuilder(
            javaExecutable(), "-cp", fixtureClasspath(), ExecutorTransitionFixture::class.java.name
        ).redirectErrorStream(true).start()
        val reader = thread(isDaemon = true, name = "launcher-transition-test-output") {
            process.inputStream.copyTo(output)
        }
        try {
            assertTrue(awaitOutput(output, "between children"), output.toString(Charsets.UTF_8))

            val signal = ProcessBuilder("kill", "-TERM", process.pid().toString()).start()
            assertTrue(signal.waitFor(2, TimeUnit.SECONDS) && signal.exitValue() == 0)
            assertTrue(process.waitFor(4, TimeUnit.SECONDS), "launcher did not stop between children")
            reader.join(1_000)

            val text = output.toString(Charsets.UTF_8)
            assertEquals(1, Regex("Fluxzero dev stopped\\.").findAll(text).count(), text)
            assertTrue(text.trimEnd().endsWith("Fluxzero dev stopped."), text)
            assertEquals(143, process.exitValue())
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    private fun awaitOutput(output: ByteArrayOutputStream, expected: String): Boolean {
        val deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos()
        while (System.nanoTime() < deadline) {
            if (output.toString(Charsets.UTF_8).contains(expected)) return true
            Thread.sleep(25)
        }
        return false
    }

    private fun fixtureClasspath(): String = listOf(
        InheritedIoCommandExecutor::class.java,
        InheritedIoCommandExecutorTest::class.java,
        Unit::class.java
    ).map { Path.of(it.protectionDomain.codeSource.location.toURI()).toString() }
        .distinct()
        .joinToString(File.pathSeparator)

    private fun javaExecutable(): String = Path.of(
        System.getProperty("java.home"), "bin",
        if (System.getProperty("os.name").lowercase().contains("win")) "java.exe" else "java"
    ).toString()
}

object ExecutorParentFixture {
    @JvmStatic
    fun main(args: Array<String>) {
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val executor = InheritedIoCommandExecutor()
        executor.supervise(onShutdown = { println("Fluxzero dev stopped.") }) {
            executor.execute(
                listOf(java, "-cp", System.getProperty("java.class.path"), ExecutorChildFixture::class.java.name),
                Path.of("").toAbsolutePath(),
                OutputMode.INHERIT
            )
        }
    }
}

object ExecutorTransitionFixture {
    @JvmStatic
    fun main(args: Array<String>) {
        InheritedIoCommandExecutor().supervise(onShutdown = { println("Fluxzero dev stopped.") }) {
            println("between children")
            System.out.flush()
            CountDownLatch(1).await()
        }
    }
}

object ExecutorChildFixture {
    @JvmStatic
    fun main(args: Array<String>) {
        Runtime.getRuntime().addShutdownHook(Thread {
            Thread.sleep(200)
            println("child stopped")
        })
        println("child ready")
        System.out.flush()
        CountDownLatch(1).await()
    }
}
