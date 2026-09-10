package host.flux.dev

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorkspaceDevStartCoordinatorTest {
    @TempDir lateinit var projectDirectory: Path

    @Test
    fun `workspace start coordinator serializes concurrent clients`() {
        val active = AtomicInteger()
        val maximumActive = AtomicInteger()
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondEntered = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit<Int> {
                WorkspaceDevStartCoordinator.start(projectDirectory) {
                    maximumActive.accumulateAndGet(active.incrementAndGet(), ::maxOf)
                    firstEntered.countDown()
                    releaseFirst.await(5, TimeUnit.SECONDS)
                    active.decrementAndGet()
                    1
                }
            }
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS))
            val second = executor.submit<Int> {
                WorkspaceDevStartCoordinator.start(projectDirectory) {
                    maximumActive.accumulateAndGet(active.incrementAndGet(), ::maxOf)
                    secondEntered.countDown()
                    active.decrementAndGet()
                    2
                }
            }

            assertTrue(!secondEntered.await(150, TimeUnit.MILLISECONDS))
            releaseFirst.countDown()
            assertEquals(1, first.get(5, TimeUnit.SECONDS))
            assertEquals(2, second.get(5, TimeUnit.SECONDS))
            assertEquals(1, maximumActive.get())
        } finally {
            releaseFirst.countDown()
            executor.shutdownNow()
        }
    }
}
