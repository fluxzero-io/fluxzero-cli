package host.flux.templates.services

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.zip.ZipInputStream

internal object ZipTemplateExtractor {
    fun extract(zipStream: InputStream, targetDir: Path) {
        val targetRoot = targetDir.toAbsolutePath().normalize()
        require(!Files.isSymbolicLink(targetRoot)) {
            "Template target must not be a symbolic link: $targetRoot"
        }
        Files.createDirectories(targetRoot)

        ZipInputStream(zipStream).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.isDirectory && (entry.name == "." || entry.name == "./")) {
                    zip.closeEntry()
                    entry = zip.nextEntry
                    continue
                }
                val outPath = resolveEntry(targetRoot, entry.name)
                requireNoSymbolicLink(targetRoot, outPath)

                if (entry.isDirectory) {
                    Files.createDirectories(outPath)
                } else {
                    Files.createDirectories(outPath.parent)
                    requireNoSymbolicLink(targetRoot, outPath)
                    Files.newOutputStream(outPath).use { out -> zip.copyTo(out) }
                }

                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }

    internal fun resolveEntry(targetRoot: Path, entryName: String): Path {
        require(entryName.isNotBlank()) { "Template archive contains an empty entry name" }
        val outPath = targetRoot.resolve(entryName).normalize()
        require(outPath != targetRoot && outPath.startsWith(targetRoot)) {
            "Template archive entry escapes the target directory: $entryName"
        }
        return outPath
    }

    private fun requireNoSymbolicLink(targetRoot: Path, outPath: Path) {
        var current: Path? = outPath
        while (current != null && current.startsWith(targetRoot)) {
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(current)) {
                throw IllegalArgumentException("Template archive entry crosses a symbolic link: $current")
            }
            if (current == targetRoot) return
            current = current.parent
        }
    }
}
