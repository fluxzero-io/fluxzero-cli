package host.flux.dev

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Path
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@EnabledOnOs(OS.LINUX, OS.MAC)
class InheritedIoCommandExecutorTest {
    @Test
    fun `global launchd cleanup only selects Fluxzero dev jobs`() {
        val output = """
            PID Status Label
            123 0 io.fluxzero.dev.abc123
            - 0 com.apple.unrelated
            456 0 io.fluxzero.dev.def456
        """.trimIndent()

        assertEquals(
            listOf("io.fluxzero.dev.abc123", "io.fluxzero.dev.def456"),
            fluxzeroLaunchdLabels(output)
        )
    }

    @Test
    fun `cleanup command can run after supervised shutdown was requested`() {
        val directory = Files.createTempDirectory("fluxzero-cleanup-command")
        val marker = directory.resolve("cleanup-ran")
        val process = ProcessBuilder(
            javaExecutable(), "-cp", fixtureClasspath(), CleanupExecutorFixture::class.java.name,
            directory.toString(), marker.toString()
        ).redirectErrorStream(true).start()
        assertEquals("ready", process.inputStream.bufferedReader().readLine())
        val signal = ProcessBuilder("kill", "-TERM", process.pid().toString()).start()
        assertTrue(signal.waitFor(2, TimeUnit.SECONDS) && signal.exitValue() == 0)
        assertTrue(process.waitFor(4, TimeUnit.SECONDS), "cleanup fixture did not stop")
        assertEquals("done", Files.readString(marker))
    }

    @Test
    fun `enables native access for build wrapper processes`() {
        val directory = Files.createTempDirectory("fluxzero-maven-options")
        val mavenWrapper = directory.resolve("mvnw")
        Files.writeString(mavenWrapper, "#!/bin/sh\nprintf '%s' \"\$MAVEN_OPTS\" > maven-opts.txt\n")
        mavenWrapper.toFile().setExecutable(true)
        val gradleWrapper = directory.resolve("gradlew")
        Files.writeString(gradleWrapper, "#!/bin/sh\nprintf '%s' \"\$GRADLE_OPTS\" > gradle-opts.txt\n")
        gradleWrapper.toFile().setExecutable(true)

        val executor = InheritedIoCommandExecutor()
        assertEquals(0, executor.execute(listOf(mavenWrapper.toString()), directory, OutputMode.INHERIT))
        assertEquals(0, executor.execute(listOf(gradleWrapper.toString()), directory, OutputMode.INHERIT))

        val mavenOptions = Files.readString(directory.resolve("maven-opts.txt"))
        val gradleOptions = Files.readString(directory.resolve("gradle-opts.txt"))
        assertTrue(mavenOptions.contains("--enable-native-access=ALL-UNNAMED"))
        assertTrue(gradleOptions.contains("--enable-native-access=ALL-UNNAMED"))
        if (Runtime.version().feature() >= 24) {
            assertTrue(mavenOptions.contains("--sun-misc-unsafe-memory-access=allow"))
            assertTrue(gradleOptions.contains("--sun-misc-unsafe-memory-access=allow"))
        }
    }

    @Test
    fun `detached child survives launcher scope and writes bootstrap log`() {
        val directory = Files.createTempDirectory("fluxzero-detached-executor")
        val log = directory.resolve("bootstrap.log")
        val executor = InheritedIoCommandExecutor()

        val pid = withShellDetach {
            executor.supervise(onShutdown = { }) {
                executor.startDetached(
                    listOf(javaExecutable(), "-cp", fixtureClasspath(), ExecutorChildFixture::class.java.name),
                    directory,
                    log
                )
            }
        }

        val process = ProcessHandle.of(pid).orElseThrow()
        try {
            assertTrue(awaitFileOutput(log, "child ready"), Files.readString(log))
            assertTrue(process.isAlive)
        } finally {
            process.descendants().toList().asReversed().forEach(ProcessHandle::destroyForcibly)
            process.destroyForcibly()
        }
    }

    @Test
    fun `detached child survives launcher process exit`() {
        val directory = Files.createTempDirectory("fluxzero-detached-parent")
        val log = directory.resolve("bootstrap.log")
        val parent = ProcessBuilder(
            javaExecutable(), "-Dfluxzero.dev.detach.shell=true", "-cp", fixtureClasspath(),
            DetachedExecutorParentFixture::class.java.name,
            directory.toString(), log.toString()
        ).redirectErrorStream(true).start()
        val pid = parent.inputStream.bufferedReader().readLine().trim().toLong()
        assertTrue(parent.waitFor(3, TimeUnit.SECONDS), "detached launcher parent did not exit")

        val process = ProcessHandle.of(pid).orElseThrow()
        try {
            assertTrue(awaitFileOutput(log, "child ready"), Files.readString(log))
            assertTrue(process.isAlive)
        } finally {
            process.descendants().toList().asReversed().forEach(ProcessHandle::destroyForcibly)
            process.destroyForcibly()
        }
    }

    @Test
    fun `sigint reports stopping before its child and stopped afterwards`() {
        val output = ByteArrayOutputStream()
        val process = ProcessBuilder(
            javaExecutable(), "-cp", fixtureClasspath(), ExecutorParentFixture::class.java.name
        ).redirectErrorStream(true).start()
        val reader = thread(isDaemon = true, name = "launcher-shutdown-test-output") {
            process.inputStream.copyTo(output)
        }
        try {
            assertTrue(awaitOutput(output, "child ready"), output.toString(Charsets.UTF_8))

            val signal = ProcessBuilder("kill", "-INT", process.pid().toString()).start()
            assertTrue(signal.waitFor(2, TimeUnit.SECONDS) && signal.exitValue() == 0)
            assertTrue(process.waitFor(4, TimeUnit.SECONDS), "launcher did not stop after one signal")
            reader.join(1_000)

            val text = output.toString(Charsets.UTF_8)
            val launcherStopping = text.indexOf("Stopping Fluxzero dev server")
            val childStopped = text.indexOf("child stopped")
            val launcherStopped = text.indexOf("Fluxzero dev server stopped.")
            assertTrue(launcherStopping >= 0 && childStopped > launcherStopping, text)
            assertTrue(launcherStopped > childStopped, text)
            assertTrue(text.trimEnd().endsWith("Fluxzero dev server stopped."), text)
            assertEquals(130, process.exitValue())
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
            val stopping = text.indexOf("Stopping Fluxzero dev server")
            val stopped = text.indexOf("Fluxzero dev server stopped.")
            assertTrue(stopping >= 0 && stopped > stopping, text)
            assertEquals(1, Regex("Fluxzero dev server stopped\\.").findAll(text).count(), text)
            assertTrue(text.trimEnd().endsWith("Fluxzero dev server stopped."), text)
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

    private fun awaitFileOutput(file: Path, expected: String): Boolean {
        val deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos()
        while (System.nanoTime() < deadline) {
            if (Files.isRegularFile(file) && Files.readString(file).contains(expected)) return true
            Thread.sleep(25)
        }
        return false
    }

    private fun <T> withShellDetach(block: () -> T): T {
        val key = "fluxzero.dev.detach.shell"
        val previous = System.getProperty(key)
        System.setProperty(key, "true")
        return try {
            block()
        } finally {
            if (previous == null) System.clearProperty(key) else System.setProperty(key, previous)
        }
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
        executor.supervise(
            onShutdownStarted = { println("Stopping Fluxzero dev server and all started applications...") },
            onShutdownComplete = { println("Fluxzero dev server stopped.") }
        ) {
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
        InheritedIoCommandExecutor().supervise(
            onShutdownStarted = { println("Stopping Fluxzero dev server and all started applications...") },
            onShutdownComplete = { println("Fluxzero dev server stopped.") }
        ) {
            println("between children")
            System.out.flush()
            CountDownLatch(1).await()
        }
    }
}

object CleanupExecutorFixture {
    @JvmStatic
    fun main(args: Array<String>) {
        val directory = Path.of(args[0])
        val marker = Path.of(args[1])
        val executor = InheritedIoCommandExecutor()
        executor.supervise(
            onShutdown = {
                executor.executeCleanup(
                    listOf("/bin/sh", "-c", "printf done > '${marker}'"),
                    directory,
                    OutputMode.DISCARD
                )
            }
        ) {
            println("ready")
            System.out.flush()
            CountDownLatch(1).await()
        }
    }
}

object DetachedExecutorParentFixture {
    @JvmStatic
    fun main(args: Array<String>) {
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val pid = InheritedIoCommandExecutor().startDetached(
            listOf(java, "-cp", System.getProperty("java.class.path"), ExecutorChildFixture::class.java.name),
            Path.of(args[0]),
            Path.of(args[1])
        )
        println(pid)
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
