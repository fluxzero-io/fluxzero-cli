package host.flux.projectfiles

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProjectFilesSyncCoordinatorTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `serializes project sync across JVM processes`() {
        val readyFile = tempDir.resolve("child-ready")
        val releaseFile = tempDir.resolve("release-child")
        val parentAcquiredFile = tempDir.resolve("parent-acquired")
        val javaExecutable = Path.of(
            System.getProperty("java.home"),
            "bin",
            if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java"
        )
        val child = ProcessBuilder(
            javaExecutable.toString(),
            "-cp",
            System.getProperty("java.class.path"),
            ProjectFilesLockProbe::class.java.name,
            tempDir.toString(),
            readyFile.toString(),
            releaseFile.toString()
        ).redirectErrorStream(true).start()
        val executor = Executors.newSingleThreadExecutor()
        val parentStarted = CountDownLatch(1)

        try {
            waitForFile(readyFile)
            val parent = executor.submit {
                parentStarted.countDown()
                ProjectFilesSyncCoordinator.withProjectLock(tempDir) {
                    Files.writeString(parentAcquiredFile, "acquired")
                }
            }

            assertTrue(parentStarted.await(5, TimeUnit.SECONDS))
            Thread.sleep(200)
            assertFalse(Files.exists(parentAcquiredFile))

            Files.writeString(releaseFile, "release")
            assertTrue(child.waitFor(5, TimeUnit.SECONDS), "Child lock probe did not stop")
            assertEquals(0, child.exitValue(), child.inputStream.bufferedReader().readText())
            parent.get(5, TimeUnit.SECONDS)
            assertTrue(Files.exists(parentAcquiredFile))
        } finally {
            Files.writeString(releaseFile, "release")
            child.destroyForcibly()
            executor.shutdownNow()
        }
    }

    private fun waitForFile(path: Path) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!Files.exists(path) && System.nanoTime() < deadline) {
            Thread.sleep(25)
        }
        assertTrue(Files.exists(path), "Child lock probe did not acquire the lock")
    }
}

object ProjectFilesLockProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        val projectDir = Path.of(args[0])
        val readyFile = Path.of(args[1])
        val releaseFile = Path.of(args[2])

        ProjectFilesSyncCoordinator.withProjectLock(projectDir) {
            Files.writeString(readyFile, "ready")
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (!Files.exists(releaseFile) && System.nanoTime() < deadline) {
                Thread.sleep(25)
            }
        }
    }
}
