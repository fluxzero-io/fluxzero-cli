package host.flux.dev

import java.nio.channels.FileChannel
import java.nio.channels.FileLockInterruptionException
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import java.nio.file.Files
import java.nio.file.Path

/** Compatibility startup lock for distributions without DevServerBootstrapMain. */
internal object WorkspaceDevStartCoordinator {
    private val processLocks = ConcurrentHashMap<Path, ReentrantLock>()

    fun start(root: Path, action: () -> Int): Int {
        val lockFile = root.toAbsolutePath().normalize().resolve(".fluxzero/dev/ensure.lock")
        Files.createDirectories(lockFile.parent)
        val processLock = processLocks.computeIfAbsent(lockFile) { ReentrantLock() }
        processLock.lockInterruptibly()
        try {
            FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
                try {
                    channel.lock().use { return action() }
                } catch (e: FileLockInterruptionException) {
                    throw InterruptedException("Interrupted while acquiring the Fluxzero dev start lock").apply {
                        initCause(e)
                    }
                }
            }
        } finally {
            processLock.unlock()
        }
    }
}
