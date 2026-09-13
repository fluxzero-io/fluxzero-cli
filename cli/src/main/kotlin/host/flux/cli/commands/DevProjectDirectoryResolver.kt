package host.flux.cli.commands

import java.nio.file.Files
import java.nio.file.Path

internal object DevProjectDirectoryResolver {
    private val buildFiles = listOf(
        "pom.xml",
        "build.gradle",
        "build.gradle.kts",
        "settings.gradle",
        "settings.gradle.kts"
    )

    fun resolveDefault(directory: Path): Path {
        val root = directory.toAbsolutePath().normalize()
        if (isBuildProject(root) || !Files.isDirectory(root)) return root

        val candidates = Files.list(root).use { entries ->
            entries
                .filter(Files::isDirectory)
                .filter { !it.fileName.toString().startsWith(".") }
                .filter(::isBuildProject)
                .sorted()
                .toList()
        }
        if (candidates.size <= 1) return candidates.singleOrNull() ?: root

        val fluxzeroCandidates = candidates.filter(::isFluxzeroProject)
        if (fluxzeroCandidates.size == 1) return fluxzeroCandidates.single()

        val choices = (fluxzeroCandidates.ifEmpty { candidates }).joinToString(", ") {
            root.relativize(it).toString()
        }
        throw IllegalArgumentException(
            "Multiple project directories were found ($choices). Select one with --project-dir."
        )
    }

    fun isBuildProject(directory: Path): Boolean = buildFiles.any {
        Files.isRegularFile(directory.resolve(it))
    }

    private fun isFluxzeroProject(directory: Path): Boolean {
        if (Files.isRegularFile(directory.resolve(".fluxzero/dev.yaml"))) return true
        return buildFiles.asSequence()
            .map(directory::resolve)
            .filter(Files::isRegularFile)
            .any { buildFile ->
                try {
                    Files.readString(buildFile).contains("io.fluxzero")
                } catch (_: Exception) {
                    false
                }
            }
    }
}
