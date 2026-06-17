package host.flux.publishing

import com.google.cloud.tools.jib.api.Containerizer
import com.google.cloud.tools.jib.api.DockerDaemonImage
import com.google.cloud.tools.jib.api.Jib
import com.google.cloud.tools.jib.api.JibContainerBuilder
import com.google.cloud.tools.jib.api.RegistryImage
import com.google.cloud.tools.jib.api.buildplan.AbsoluteUnixPath
import com.google.cloud.tools.jib.api.buildplan.ContainerBuildPlan
import com.google.cloud.tools.jib.api.buildplan.FileEntriesLayer
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.streams.asSequence

fun interface PackagePublisher {
    fun publish(spec: JavaPackagePublishSpec): PackagePublishResult
}

class JavaPackagePublisher : PackagePublisher {
    override fun publish(spec: JavaPackagePublishSpec): PackagePublishResult {
        spec.validate()

        val packageReference = PackageNameSupport.packageReference(
            spec.registryHost,
            spec.teamId,
            spec.packageName,
            spec.packageVersion
        )
        val builder = createContainerBuilder(spec)

        val targetImage = RegistryImage.named(packageReference)
            .addCredential("fluxzero", spec.registryToken)
        val containerizer = Containerizer.to(targetImage)
            .setToolName(spec.toolName)

        val container = builder.containerize(containerizer)
        return PackagePublishResult(packageReference, container.digest.toString())
    }

    internal fun buildPlan(spec: JavaPackagePublishSpec): ContainerBuildPlan {
        spec.validate()
        return createContainerBuilder(spec).toContainerBuildPlan()
    }

    private fun createContainerBuilder(spec: JavaPackagePublishSpec): JibContainerBuilder {
        val builder = when (spec.baseImageSource) {
            BaseImageSource.REGISTRY -> Jib.from(spec.baseImage)
            BaseImageSource.DOCKER_DAEMON -> Jib.from(DockerDaemonImage.named(spec.baseImage))
        }
            .setCreationTime(JavaPackagePublishSpec.REPRODUCIBLE_CONTAINER_TIMESTAMP)
            .setWorkingDirectory(AbsoluteUnixPath.get("/app"))
            .setEntrypoint("java", "-cp", "/app/classes:/app/libs/*", spec.mainClass)
            .addLabel("org.opencontainers.image.title", spec.packageName)
            .addLabel("org.opencontainers.image.version", spec.packageVersion)
            .addLabel("io.fluxzero.package.metadata-version", "1")

        builder.addEnvironmentVariable("JAVA_TOOL_OPTIONS", spec.javaToolOptions)

        spec.applicationId?.takeIf { it.isNotBlank() }?.let {
            builder.addLabel("io.fluxzero.application-id", it)
        }
        spec.labels.forEach { (name, value) ->
            if (name.isNotBlank() && value.isNotBlank()) {
                builder.addLabel(name, value)
            }
        }

        addDependencyLayer(builder, "dependencies", spec.releaseDependencies)
        addDependencyLayer(builder, "snapshot-dependencies", spec.snapshotDependencies)
        addApplicationLayer(builder, spec.classesDirectory)
        return builder
    }

    private fun addDependencyLayer(
        builder: JibContainerBuilder,
        name: String,
        dependencies: List<Path>
    ) {
        if (dependencies.isEmpty()) {
            return
        }
        val layerBuilder = FileEntriesLayer.builder().setName(name)
        dependencies.forEach { dependency ->
            layerBuilder.addEntry(
                dependency,
                AbsoluteUnixPath.get("/app/libs/${dependency.fileName}"),
                JavaPackagePublishSpec.REPRODUCIBLE_FILE_TIMESTAMP
            )
        }
        builder.addFileEntriesLayer(layerBuilder.build())
    }

    private fun addApplicationLayer(
        builder: JibContainerBuilder,
        classesDirectory: Path
    ) {
        val targetRoot = AbsoluteUnixPath.get("/app/classes")
        val layerBuilder = FileEntriesLayer.builder().setName("application")
        Files.walk(classesDirectory).use { paths ->
            paths.asSequence()
                .filter { Files.isRegularFile(it) }
                .sortedBy { normalizedRelativePath(classesDirectory, it) }
                .forEach { file ->
                    layerBuilder.addEntry(
                        file,
                        targetRoot.resolve(normalizedRelativePath(classesDirectory, file)),
                        JavaPackagePublishSpec.REPRODUCIBLE_FILE_TIMESTAMP
                    )
                }
        }
        builder.addFileEntriesLayer(layerBuilder.build())
    }

    private fun normalizedRelativePath(root: Path, file: Path): String =
        root.relativize(file).joinToString("/")
}

enum class BaseImageSource {
    REGISTRY,
    DOCKER_DAEMON;

    companion object {
        fun parse(value: String): BaseImageSource =
            when (value.trim().lowercase().replace("_", "-")) {
                "registry" -> REGISTRY
                "docker-daemon", "docker" -> DOCKER_DAEMON
                else -> throw IllegalArgumentException(
                    "Invalid base image source '$value'. Expected 'registry' or 'docker-daemon'."
                )
            }
    }
}

data class JavaPackagePublishSpec(
    val registryHost: String = PackageNameSupport.DEFAULT_REGISTRY_HOST,
    val registryToken: String,
    val teamId: String? = null,
    val packageName: String,
    val packageVersion: String,
    val applicationId: String? = null,
    val mainClass: String,
    val baseImage: String = DEFAULT_BASE_IMAGE,
    val baseImageSource: BaseImageSource = BaseImageSource.REGISTRY,
    val javaToolOptions: String = DEFAULT_JAVA_TOOL_OPTIONS,
    val classesDirectory: Path,
    val releaseDependencies: List<Path> = emptyList(),
    val snapshotDependencies: List<Path> = emptyList(),
    val labels: Map<String, String> = emptyMap(),
    val toolName: String = "fluxzero-publishing"
) {
    companion object {
        val REPRODUCIBLE_CONTAINER_TIMESTAMP: Instant = Instant.EPOCH
        val REPRODUCIBLE_FILE_TIMESTAMP: Instant = Instant.EPOCH

        const val DEFAULT_BASE_IMAGE =
            "gcr.io/distroless/java25-debian13:nonroot@sha256:f25ab728deeafec63d7176a473536f4f4347d42db7e24b3bb0fb7b05ff84d248"

        const val DEFAULT_JAVA_TOOL_OPTIONS =
            "-XX:MaxRAMPercentage=75.0 -Djava.security.egd=file:/dev/./urandom -XX:+ExitOnOutOfMemoryError -XX:SoftRefLRUPolicyMSPerMB=2500"
    }

    fun validate() {
        require(registryHost.isNotBlank()) { "Missing registry host." }
        require(registryToken.isNotBlank()) { "Missing registry token." }
        require(!PackageNameSupport.isPlainHttpRegistryHost(registryHost)) {
            "Fluxzero registry host must use HTTPS when a registry token is sent. " +
                "Use an https:// registry host or the local TLS proxy for end-to-end tests."
        }
        teamId?.takeIf { it.isNotBlank() }?.let {
            require(PackageNameSupport.isValidTeamId(it)) { "Invalid team id '$it'." }
        }
        require(PackageNameSupport.isValidPackageName(packageName)) { "Invalid package name '$packageName'." }
        require(PackageNameSupport.isValidTag(packageVersion)) { "Invalid package version '$packageVersion'." }
        require(mainClass.isNotBlank()) { "Missing application main class." }
        require(baseImage.isNotBlank()) { "Missing Java runtime base image." }
        require(classesDirectory.toFile().isDirectory) {
            "Project output directory does not exist: ${classesDirectory.toAbsolutePath()}."
        }
    }
}

data class PackagePublishResult(
    val packageReference: String,
    val digest: String
)
