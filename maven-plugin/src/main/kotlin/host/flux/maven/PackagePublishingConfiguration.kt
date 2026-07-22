package host.flux.maven

import host.flux.publishing.JavaPackageExtraDirectory
import host.flux.publishing.JavaPackagePlatform
import host.flux.publishing.PackageNameSupport
import org.apache.maven.plugin.MojoFailureException
import org.apache.maven.project.MavenProject
import java.nio.file.Path
import java.util.Locale

internal object PackagePublishingConfigurationSupport {
    fun platforms(configuredPlatforms: List<Platform>): List<JavaPackagePlatform> =
        (configuredPlatforms.takeIf { it.isNotEmpty() }
            ?.mapIndexed { index, platform ->
                JavaPackagePlatform(
                    os = requiredValue(platform.os, "<os>", "platform ${index + 1}").lowercase(Locale.ROOT),
                    architecture = requiredValue(
                        platform.architecture,
                        "<architecture>",
                        "platform ${index + 1}"
                    ).lowercase(Locale.ROOT)
                )
            }
            ?: listOf(JavaPackagePlatform.DEFAULT))
            .onEach(JavaPackagePlatform::validate)

    fun extraDirectories(
        configuredDirectories: List<ExtraDirectory>,
        projectDirectory: Path
    ): List<JavaPackageExtraDirectory> = configuredDirectories.mapIndexed { index, directory ->
        val description = "extra directory ${index + 1}"
        val configuredSource = requiredValue(directory.from, "<from>", description)
        val source = Path.of(configuredSource).let { path ->
            if (path.isAbsolute) path.normalize() else projectDirectory.resolve(path).normalize()
        }
        JavaPackageExtraDirectory(
            source = source,
            containerPath = requiredValue(directory.into, "<into>", description)
        )
    }

    fun labels(includeDefaults: Boolean, configuredLabels: List<Label>): ResolvedLabels {
        val duplicateKeys = configuredLabels
            .mapIndexed { index, label -> requiredValue(label.key, "<key>", "label ${index + 1}") }
            .groupingBy { it }
            .eachCount()
            .filterValues { it > 1 }
            .keys
        if (duplicateKeys.isNotEmpty()) {
            throw MojoFailureException("Configure each package label at most once. Duplicate: ${duplicateKeys.sorted().joinToString()}.")
        }
        val overrides = configuredLabels.associate { label ->
            label.key!!.trim() to label.value
        }
        return ResolvedLabels(
            includeDefaults = includeDefaults,
            overrides = overrides
        )
    }

    private fun requiredValue(value: String?, element: String, description: String): String =
        value?.trim()?.takeIf { it.isNotBlank() }
            ?: throw MojoFailureException("Missing $element for $description.")
}

internal data class ResolvedLabels(
    val includeDefaults: Boolean,
    val overrides: Map<String, String?>
)

internal object MavenPackageLabels {
    fun defaults(project: MavenProject, gitInfo: PackageNameSupport.GitInfo?): Map<String, String> = buildMap {
        put("io.fluxzero.maven.group-id", project.groupId)
        put("io.fluxzero.maven.artifact-id", project.artifactId)
        put("io.fluxzero.maven.version", project.version)
        gitInfo?.sha?.takeIf { it.isNotBlank() }?.let { put("org.opencontainers.image.revision", it) }
        val sourceUrl = PackageNameSupport.normalizeRepositoryUrl(project.scm?.url) ?: gitInfo?.remoteUrl
        sourceUrl?.let { put("org.opencontainers.image.source", it) }
        project.url?.trim()?.takeIf { it.isNotBlank() }?.let { put("org.opencontainers.image.url", it) }
        project.description?.trim()?.takeIf { it.isNotBlank() }?.let {
            put("org.opencontainers.image.description", it)
        }
    }
}

/** One `<platform>` item under `<platforms>`. */
class Platform {
    var os: String? = null
    var architecture: String? = null
}

/** One `<extraDirectory>` item under `<extraDirectories>`. */
class ExtraDirectory {
    var from: String? = null
    var into: String? = null
}

/** One `<label>` item under `<labels>`. Omitting `<value>` removes the label. */
class Label {
    var key: String? = null
    var value: String? = null
}
