package host.flux.projectfiles

import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Serializes project-files updates within this JVM and across build processes.
 */
internal object ProjectFilesSyncCoordinator {
    private val processLocks = ConcurrentHashMap<Path, ReentrantLock>()

    fun <T> withProjectLock(projectDir: Path, action: () -> T): T {
        val canonicalProjectDir = try {
            projectDir.toRealPath()
        } catch (_: Exception) {
            projectDir.toAbsolutePath().normalize()
        }
        val lockId = stableHash(canonicalProjectDir.toString())
        val userScope = stableHash(System.getProperty("user.home", "unknown")).take(16)
        val lockPath = Path.of(
            System.getProperty("java.io.tmpdir"), "fluxzero-$userScope", "project-files-locks", "$lockId.lock"
        )
        val processLock = processLocks.computeIfAbsent(lockPath) { ReentrantLock() }

        return processLock.withLock {
            val channel = try {
                Files.createDirectories(lockPath.parent)
                FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            } catch (e: Exception) {
                throw ProjectFilesSyncException("Could not lock project files for $projectDir", e)
            }
            channel.use {
                val fileLock = acquireFileLock(channel, projectDir)
                fileLock.use { action() }
            }
        }
    }

    private fun acquireFileLock(channel: FileChannel, projectDir: Path): FileLock {
        while (true) {
            try {
                return channel.lock()
            } catch (_: OverlappingFileLockException) {
                try {
                    Thread.sleep(25)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw ProjectFilesSyncException("Interrupted while waiting to lock project files for $projectDir", e)
                }
            } catch (e: Exception) {
                throw ProjectFilesSyncException("Could not lock project files for $projectDir", e)
            }
        }
    }

    private fun stableHash(value: String): String = HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8))
    )
}

internal class ProjectFilesSyncException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
