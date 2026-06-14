package host.flux.publishing

import com.google.cloud.tools.jib.api.Containerizer
import com.google.cloud.tools.jib.api.Jib
import com.google.cloud.tools.jib.api.RegistryImage
import com.google.cloud.tools.jib.api.buildplan.AbsoluteUnixPath
import com.google.cloud.tools.jib.api.buildplan.FileEntriesLayer
import java.nio.file.Path

fun interface ImagePublisher {
    fun publish(spec: JavaImagePublishSpec): ImagePublishResult
}

class JavaImagePublisher : ImagePublisher {
    override fun publish(spec: JavaImagePublishSpec): ImagePublishResult {
        spec.validate()

        val imageReference = ImageNameSupport.imageReference(spec.registryHost, spec.imageName, spec.imageVersion)
        val builder = Jib.from(spec.baseImage)
            .setWorkingDirectory(AbsoluteUnixPath.get("/app"))
            .setEntrypoint("/usr/bin/java", "-cp", "/app/classes:/app/libs/*", spec.mainClass)
            .addEnvironmentVariable(
                "JAVA_TOOL_OPTIONS",
                "-XX:MaxRAMPercentage=75.0 -Djava.security.egd=file:/dev/./urandom " +
                    "-XX:+ExitOnOutOfMemoryError -XX:SoftRefLRUPolicyMSPerMB=2500"
            )
            .addLabel("org.opencontainers.image.title", spec.imageName)
            .addLabel("org.opencontainers.image.version", spec.imageVersion)
            .addLabel("io.fluxzero.image.metadata-version", "1")

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
        builder.addFileEntriesLayer(
            FileEntriesLayer.builder()
                .setName("application")
                .addEntryRecursive(spec.classesDirectory, AbsoluteUnixPath.get("/app/classes"))
                .build()
        )

        val targetImage = RegistryImage.named(imageReference)
            .addCredential("fluxzero", spec.registryToken)
        val containerizer = Containerizer.to(targetImage)
            .setToolName(spec.toolName)

        val container = builder.containerize(containerizer)
        return ImagePublishResult(imageReference, container.digest.toString())
    }

    private fun addDependencyLayer(
        builder: com.google.cloud.tools.jib.api.JibContainerBuilder,
        name: String,
        dependencies: List<Path>
    ) {
        if (dependencies.isEmpty()) {
            return
        }
        val layerBuilder = FileEntriesLayer.builder().setName(name)
        dependencies.forEach { dependency ->
            layerBuilder.addEntry(dependency, AbsoluteUnixPath.get("/app/libs/${dependency.fileName}"))
        }
        builder.addFileEntriesLayer(layerBuilder.build())
    }
}

data class JavaImagePublishSpec(
    val registryHost: String = ImageNameSupport.DEFAULT_REGISTRY_HOST,
    val registryToken: String,
    val imageName: String,
    val imageVersion: String,
    val applicationId: String? = null,
    val mainClass: String,
    val baseImage: String = DEFAULT_BASE_IMAGE,
    val classesDirectory: Path,
    val releaseDependencies: List<Path> = emptyList(),
    val snapshotDependencies: List<Path> = emptyList(),
    val labels: Map<String, String> = emptyMap(),
    val toolName: String = "fluxzero-publishing"
) {
    companion object {
        const val DEFAULT_BASE_IMAGE =
            "gcr.io/distroless/java25-debian13:nonroot@sha256:f25ab728deeafec63d7176a473536f4f4347d42db7e24b3bb0fb7b05ff84d248"
    }

    fun validate() {
        require(registryHost.isNotBlank()) { "Missing registry host." }
        require(registryToken.isNotBlank()) { "Missing registry token." }
        require(!ImageNameSupport.isPlainHttpRegistryHost(registryHost)) {
            "Fluxzero image registry host must use HTTPS when a registry token is sent. " +
                "Use an https:// registry host or the local TLS proxy for end-to-end tests."
        }
        require(ImageNameSupport.isValidImageName(imageName)) { "Invalid image name '$imageName'." }
        require(ImageNameSupport.isValidTag(imageVersion)) { "Invalid image version '$imageVersion'." }
        require(mainClass.isNotBlank()) { "Missing application main class." }
        require(classesDirectory.toFile().isDirectory) {
            "Project output directory does not exist: ${classesDirectory.toAbsolutePath()}."
        }
    }
}

data class ImagePublishResult(
    val imageReference: String,
    val digest: String
)
