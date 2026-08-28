package host.flux.templates.services

import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException

internal object InPlaceScaffoldPublisher {
    fun acquireTargetLock(outputDir: Path): AutoCloseable {
        val target = outputDir.toAbsolutePath().normalize()
        val parent = target.parent ?: throw IllegalArgumentException("Cannot scaffold in place at filesystem root")
        val targetName = target.fileName?.toString()
            ?: throw IllegalArgumentException("Cannot scaffold in place at filesystem root")
        Files.createDirectories(parent)
        val lockFile = parent.resolve(".$targetName.fluxzero-scaffold.lock")
        require(!Files.isSymbolicLink(lockFile)) { "In-place scaffold lock must not be a symbolic link: $lockFile" }
        val channel = FileChannel.open(
            lockFile,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS
        )
        val lock = try {
            channel.tryLock()
        } catch (_: OverlappingFileLockException) {
            null
        }
        if (lock == null) {
            channel.close()
            throw IllegalStateException("Another in-place scaffold is already in progress for $target")
        }
        return ScaffoldTargetLock(channel, lock)
    }

    fun createStagingDirectory(outputDir: Path): Path {
        val target = outputDir.toAbsolutePath().normalize()
        val parent = target.parent ?: throw IllegalArgumentException("Cannot scaffold in place at filesystem root")
        Files.createDirectories(parent)
        return Files.createTempDirectory(parent, ".fluxzero-scaffold-")
    }

    fun validateStagedTemplate(templateRoot: Path) {
        val root = templateRoot.toAbsolutePath().normalize()
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                validateStagedPath(root, dir)
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                validateStagedPath(root, file)
                return FileVisitResult.CONTINUE
            }
        })
    }

    fun publish(
        templateRoot: Path,
        outputDir: Path,
        move: (Path, Path) -> Unit = ::moveWithoutReplacement
    ) {
        val sourceRoot = templateRoot.toAbsolutePath().normalize()
        val targetRoot = outputDir.toAbsolutePath().normalize()
        if (!Files.exists(targetRoot, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectory(targetRoot)
        }
        require(!Files.isSymbolicLink(targetRoot)) { "In-place target must not be a symbolic link: $targetRoot" }
        require(isAvailableTarget(targetRoot)) {
            "In-place target contains files other than managed .fluxzero/dev state: $targetRoot"
        }

        validatePublishCollisions(sourceRoot, targetRoot)
        val published = mutableListOf<PublishedPath>()
        try {
            Files.list(sourceRoot).use { entries ->
                entries.sorted(publishOrder())
                    .forEach { source -> moveIntoTarget(source, targetRoot.resolve(source.fileName), published, move) }
            }
        } catch (failure: Exception) {
            published.asReversed().forEach { path ->
                try {
                    Files.createDirectories(path.source.parent)
                    Files.move(path.target, path.source)
                } catch (rollbackFailure: Exception) {
                    failure.addSuppressed(rollbackFailure)
                }
            }
            throw failure
        }
    }

    fun deleteStagingDirectory(root: Path) {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.deleteIfExists(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                if (exc != null) throw exc
                Files.deleteIfExists(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }

    fun isAvailableTarget(outputDir: Path): Boolean {
        if (!Files.isDirectory(outputDir) || Files.isSymbolicLink(outputDir)) return false
        return Files.list(outputDir).use { children ->
            children.allMatch(::isManagedDevState)
        }
    }

    private fun isManagedDevState(path: Path): Boolean {
        if (path.fileName.toString() != ".fluxzero" || Files.isSymbolicLink(path) || !Files.isDirectory(path)) {
            return false
        }
        return Files.list(path).use { stream ->
            val children = stream.toList()
            children.size == 1 && children.single().let { child ->
                child.fileName.toString() == "dev" && !Files.isSymbolicLink(child) && Files.isDirectory(child)
            }
        }
    }

    private fun validateStagedPath(root: Path, path: Path) {
        val normalized = path.toAbsolutePath().normalize()
        require(normalized.startsWith(root)) { "Template path escapes the staging directory: $path" }
        require(!Files.isSymbolicLink(path)) {
            "Templates must not contain symbolic links: ${root.relativize(normalized)}"
        }
        require(!isManagedDevAlias(root.relativize(normalized))) {
            "Templates must not contain managed .fluxzero/dev state"
        }
    }

    private fun isManagedDevAlias(relative: Path): Boolean =
        relative.nameCount >= 2
                && relative.getName(0).toString().equals(".fluxzero", ignoreCase = true)
                && relative.getName(1).toString().equals("dev", ignoreCase = true)

    private fun validatePublishCollisions(sourceRoot: Path, targetRoot: Path) {
        Files.walkFileTree(sourceRoot, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                validateCollision(sourceRoot, targetRoot, dir)
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                validateCollision(sourceRoot, targetRoot, file)
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun validateCollision(sourceRoot: Path, targetRoot: Path, source: Path) {
        if (source == sourceRoot) return
        val relative = sourceRoot.relativize(source)
        val target = targetRoot.resolve(relative)
        requireNoSymbolicLinkAncestor(targetRoot, target)
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            require(relative.nameCount == 1
                    && relative.fileName.toString() == ".fluxzero"
                    && Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)
                    && Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isSymbolicLink(target)) {
                "Template output collides with an existing in-place path: $relative"
            }
        }
    }

    private fun requireNoSymbolicLinkAncestor(targetRoot: Path, target: Path) {
        var current: Path? = target
        while (current != null && current.startsWith(targetRoot)) {
            require(!Files.isSymbolicLink(current)) { "In-place target crosses a symbolic link: $current" }
            if (current == targetRoot) return
            current = current.parent
        }
    }

    private fun moveIntoTarget(
        source: Path,
        target: Path,
        published: MutableList<PublishedPath>,
        move: (Path, Path) -> Unit
    ) {
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            Files.list(source).use { children ->
                children.sorted(publishOrder())
                    .forEach { child -> moveIntoTarget(child, target.resolve(child.fileName), published, move) }
            }
            Files.delete(source)
            return
        }

        // The default move is deliberately non-replacing on every supported provider.
        // ATOMIC_MOVE may replace a concurrently created target on some platforms.
        move(source, target)
        published.add(PublishedPath(target = target, source = source))
    }

    private fun moveWithoutReplacement(source: Path, target: Path) {
        Files.move(source, target)
    }

    private fun publishOrder(): Comparator<Path> =
        Comparator.comparingInt<Path> { publishPriority(it.fileName.toString()) }
            .thenComparing { it.fileName.toString() }

    private fun publishPriority(fileName: String): Int = when (fileName) {
        ".fluxzero" -> 1
        "settings.gradle", "settings.gradle.kts", "gradle.properties" -> 2
        "pom.xml", "build.gradle", "build.gradle.kts" -> 3
        else -> 0
    }

    private data class PublishedPath(val target: Path, val source: Path)

    private class ScaffoldTargetLock(
        private val channel: FileChannel,
        private val lock: FileLock
    ) : AutoCloseable {
        override fun close() {
            try {
                lock.release()
            } finally {
                channel.close()
            }
        }
    }
}
