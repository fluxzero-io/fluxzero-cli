package host.flux.publishing

import com.google.cloud.tools.jib.api.Containerizer
import com.google.cloud.tools.jib.api.DockerDaemonImage
import com.google.cloud.tools.jib.api.Jib
import com.google.cloud.tools.jib.api.LogEvent
import com.google.cloud.tools.jib.api.JibContainerBuilder
import com.google.cloud.tools.jib.api.RegistryImage
import com.google.cloud.tools.jib.api.buildplan.AbsoluteUnixPath
import com.google.cloud.tools.jib.api.buildplan.ContainerBuildPlan
import com.google.cloud.tools.jib.api.buildplan.FileEntriesLayer
import com.google.cloud.tools.jib.event.events.TimerEvent
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.streams.asSequence

fun interface PackagePublisher {
    fun publish(spec: JavaPackagePublishSpec): List<PackagePublishResult>
}

class JavaPackagePublisher(private val diagnostics: JavaPackagePublishDiagnostics = JavaPackagePublishDiagnostics.NONE) : PackagePublisher {
    override fun publish(spec: JavaPackagePublishSpec): List<PackagePublishResult> {
        spec.validate()

        return spec.publishTargets().flatMap { publishTarget ->
            publishWithRetries(spec, publishTarget)
        }
    }

    private fun publishWithRetries(
        spec: JavaPackagePublishSpec,
        publishTarget: JavaPackagePublishTarget
    ): List<PackagePublishResult> {
        var attempt = 1
        while (true) {
            val credential = spec.credentialFor(publishTarget.image)
            val builder = createContainerBuilder(spec)
            val targetImage = RegistryImage.named(publishTarget.primaryReference.reference)
                .addCredential(credential.registryUsername, credential.registryToken)
            val containerizer = Containerizer.to(targetImage)
                .setToolName(spec.toolName)
            publishTarget.additionalTags.forEach { tag ->
                containerizer.withAdditionalTag(tag)
            }
            addDiagnostics(containerizer, publishTarget, attempt)

            diagnostics.record(
                JavaPackagePublishDiagnosticEvent(
                    category = "target-start",
                    targetImage = publishTarget.image,
                    targetReference = publishTarget.primaryReference.reference,
                    attempt = attempt,
                    message = "Publishing ${publishTarget.references.joinToString { it.reference }}"
                )
            )
            try {
                val container = builder.containerize(containerizer)
                diagnostics.record(
                    JavaPackagePublishDiagnosticEvent(
                        category = "target-finish",
                        targetImage = publishTarget.image,
                        targetReference = publishTarget.primaryReference.reference,
                        attempt = attempt,
                        message = "Published digest ${container.digest}"
                    )
                )
                return publishTarget.references.map { packageReference ->
                    PackagePublishResult(packageReference.reference, container.digest.toString())
                }
            } catch (exception: Exception) {
                val willRetry = attempt < spec.publishAttempts && exception.isRetriableRegistryBlobUploadFailure()
                diagnostics.record(
                    JavaPackagePublishDiagnosticEvent(
                        category = if (willRetry) "target-attempt-failure" else "target-failure",
                        targetImage = publishTarget.image,
                        targetReference = publishTarget.primaryReference.reference,
                        level = "ERROR",
                        attempt = attempt,
                        message = "${exception::class.qualifiedName}: ${exception.message}"
                    )
                )
                if (!willRetry) {
                    throw exception
                }
                val delayMillis = retryDelayMillis(spec, attempt)
                diagnostics.record(
                    JavaPackagePublishDiagnosticEvent(
                        category = "target-retry",
                        targetImage = publishTarget.image,
                        targetReference = publishTarget.primaryReference.reference,
                        attempt = attempt,
                        message = "Retrying publish after ${delayMillis}ms because the registry returned a transient blob upload-session error"
                    )
                )
                sleepBeforeRetry(delayMillis, exception)
                attempt++
            }
        }
    }

    internal fun buildPlan(spec: JavaPackagePublishSpec): ContainerBuildPlan {
        spec.validate()
        return createContainerBuilder(spec).toContainerBuildPlan()
    }

    private fun addDiagnostics(containerizer: Containerizer, publishTarget: JavaPackagePublishTarget, attempt: Int) {
        containerizer.addEventHandler(LogEvent::class.java) { event ->
            diagnostics.record(
                JavaPackagePublishDiagnosticEvent(
                    category = "jib-log",
                    targetImage = publishTarget.image,
                    targetReference = publishTarget.primaryReference.reference,
                    level = event.level.name,
                    attempt = attempt,
                    message = event.message
                )
            )
        }
        containerizer.addEventHandler(TimerEvent::class.java) { event ->
            diagnostics.record(
                JavaPackagePublishDiagnosticEvent(
                    category = "jib-timer",
                    targetImage = publishTarget.image,
                    targetReference = publishTarget.primaryReference.reference,
                    level = event.state.name,
                    attempt = attempt,
                    message = buildString {
                        append(event.description)
                        append(" durationMs=").append(event.duration.toMillis())
                        append(" elapsedMs=").append(event.elapsed.toMillis())
                    }
                )
            )
        }
    }

    private fun retryDelayMillis(spec: JavaPackagePublishSpec, failedAttempt: Int): Long =
        spec.publishRetryDelayMillis * failedAttempt.toLong()

    private fun sleepBeforeRetry(delayMillis: Long, originalFailure: Exception) {
        if (delayMillis <= 0) {
            return
        }
        try {
            TimeUnit.MILLISECONDS.sleep(delayMillis)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            originalFailure.addSuppressed(interrupted)
            throw originalFailure
        }
    }

    private fun createContainerBuilder(spec: JavaPackagePublishSpec): JibContainerBuilder {
        val dependencies = spec.orderedDependencies()
        val builder = when (spec.baseImageSource) {
            BaseImageSource.REGISTRY -> Jib.from(spec.baseImage)
            BaseImageSource.DOCKER_DAEMON -> Jib.from(DockerDaemonImage.named(spec.baseImage))
        }
            .setCreationTime(JavaPackagePublishSpec.REPRODUCIBLE_CONTAINER_TIMESTAMP)
            .setWorkingDirectory(AbsoluteUnixPath.get("/app"))
            .setEntrypoint("java", "-cp", classpath(dependencies), spec.mainClass)
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

        addApplicationLayer(builder, spec.classesDirectory)
        addDependencyLayer(builder, "dependencies", dependencies)
        return builder
    }

    private fun addDependencyLayer(
        builder: JibContainerBuilder,
        name: String,
        dependencies: List<JavaPackageDependency>
    ) {
        if (dependencies.isEmpty()) {
            return
        }
        val layerBuilder = FileEntriesLayer.builder().setName(name)
        dependencies.forEach { dependency ->
            layerBuilder.addEntry(
                dependency.source,
                AbsoluteUnixPath.get(dependency.containerPath),
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

    private fun classpath(dependencies: List<JavaPackageDependency>): String =
        (listOf("/app/classes") + dependencies.map { it.containerPath }).joinToString(":")
}

internal fun Throwable.isRetriableRegistryBlobUploadFailure(): Boolean =
    generateSequence(this) { it.cause }.any { throwable ->
        throwable.message.isRetriableRegistryBlobUploadMessage()
    }

private fun String?.isRetriableRegistryBlobUploadMessage(): Boolean {
    val message = this?.lowercase(Locale.ROOT) ?: return false
    val isBlobUploadSessionError = message.contains("blob_upload_unknown") ||
        message.contains("blob upload unknown") ||
        message.contains("blob_unknown") ||
        message.contains("blob unknown to registry")
    val isBlobUploadOperation = message.contains("push blob") || message.contains("/blobs/upload/")
    return isBlobUploadSessionError && isBlobUploadOperation
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
    val registryUsername: String = DEFAULT_REGISTRY_USERNAME,
    val registryToken: String? = null,
    val teamId: String? = null,
    val packageName: String,
    val packageVersion: String,
    val applicationId: String? = null,
    val mainClass: String,
    val baseImage: String = DEFAULT_BASE_IMAGE,
    val baseImageSource: BaseImageSource = BaseImageSource.REGISTRY,
    val javaToolOptions: String = DEFAULT_JAVA_TOOL_OPTIONS,
    val classesDirectory: Path,
    val dependencies: List<JavaPackageDependency> = emptyList(),
    val labels: Map<String, String> = emptyMap(),
    val images: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val credentials: List<JavaPackageRegistryCredential> = emptyList(),
    val toolName: String = "fluxzero-publishing",
    val publishAttempts: Int = DEFAULT_PUBLISH_ATTEMPTS,
    val publishRetryDelayMillis: Long = DEFAULT_PUBLISH_RETRY_DELAY_MILLIS
) {
    companion object {
        val REPRODUCIBLE_CONTAINER_TIMESTAMP: Instant = Instant.EPOCH
        val REPRODUCIBLE_FILE_TIMESTAMP: Instant = Instant.EPOCH

        const val DEFAULT_REGISTRY_USERNAME = "fluxzero"

        const val DEFAULT_PUBLISH_ATTEMPTS = 3
        const val DEFAULT_PUBLISH_RETRY_DELAY_MILLIS = 2000L

        const val DEFAULT_BASE_IMAGE =
            "gcr.io/distroless/java25-debian13:nonroot@sha256:f25ab728deeafec63d7176a473536f4f4347d42db7e24b3bb0fb7b05ff84d248"

        const val DEFAULT_JAVA_TOOL_OPTIONS =
            "-XX:MaxRAMPercentage=75.0 -Djava.security.egd=file:/dev/./urandom -XX:+ExitOnOutOfMemoryError -XX:SoftRefLRUPolicyMSPerMB=2500"
    }

    fun validate() {
        require(PackageNameSupport.isValidPackageName(packageName)) { "Invalid package name '$packageName'." }
        require(PackageNameSupport.isValidTag(packageVersion)) { "Invalid package version '$packageVersion'." }
        teamId?.takeIf { it.isNotBlank() }?.let {
            require(PackageNameSupport.isValidTeamId(it)) { "Invalid team id '$it'." }
        }
        require(mainClass.isNotBlank()) { "Missing application main class." }
        require(baseImage.isNotBlank()) { "Missing Java runtime base image." }
        require(publishAttempts >= 1) { "Publish attempts must be at least 1." }
        require(publishRetryDelayMillis >= 0) { "Publish retry delay must be at least 0." }
        require(classesDirectory.toFile().isDirectory) {
            "Project output directory does not exist: ${classesDirectory.toAbsolutePath()}."
        }
        orderedDependencies().forEach { dependency ->
            require(Files.isRegularFile(dependency.source)) {
                "Dependency JAR does not exist: ${dependency.source.toAbsolutePath()}."
            }
        }
        packageReferences().forEach { credentialFor(it.image) }
        resolvedCredentials().forEach { it.validate() }
    }

    fun packageReferences(): List<JavaPackageReference> =
        publishTargets().flatMap { it.references }

    internal fun publishTargets(): List<JavaPackagePublishTarget> {
        val tags = resolvedTags()
        return resolvedImages().map { image ->
            val references = tags.map { tag ->
                JavaPackageReference(image, "$image:$tag")
            }
            JavaPackagePublishTarget(
                image = image,
                primaryReference = references.first(),
                additionalTags = references.drop(1).map { it.tag },
                references = references
            )
        }
    }

    fun credentialFor(image: String): JavaPackageRegistryCredential {
        val registryHost = PackageNameSupport.registryAuthority(image)
        return resolvedCredentials().firstOrNull { credential ->
            PackageNameSupport.registryAuthority(credential.registryHost) == registryHost
        } ?: throw IllegalArgumentException("Missing registry credential for '$registryHost'.")
    }

    private fun resolvedImages(): List<String> =
        images.takeIf { it.isNotEmpty() }
            ?: listOf(PackageNameSupport.packageRepository(registryHost, teamId, packageName))

    private fun resolvedTags(): List<String> =
        tags.takeIf { it.isNotEmpty() } ?: listOf(packageVersion)

    private fun resolvedCredentials(): List<JavaPackageRegistryCredential> =
        credentials.takeIf { it.isNotEmpty() }
            ?: listOf(
                JavaPackageRegistryCredential(
                    registryHost = registryHost,
                    registryUsername = registryUsername,
                    registryToken = registryToken.orEmpty()
                )
            )

    internal fun orderedDependencies(): List<JavaPackageDependency> = dependencies
}

internal data class JavaPackagePublishTarget(
    val image: String,
    val primaryReference: JavaPackageReference,
    val additionalTags: List<String>,
    val references: List<JavaPackageReference>
)

data class JavaPackageReference(
    val image: String,
    val reference: String
) {
    val tag: String = reference.removePrefix("$image:")

    init {
        require(PackageNameSupport.isValidImageRepository(image)) { "Invalid package image '$image'." }
        require(tag != reference) { "Invalid package reference '$reference' for image '$image'." }
        require(PackageNameSupport.isValidTag(tag)) { "Invalid package tag '$tag'." }
    }
}

data class JavaPackageRegistryCredential(
    val registryHost: String = PackageNameSupport.DEFAULT_REGISTRY_HOST,
    val registryUsername: String = JavaPackagePublishSpec.DEFAULT_REGISTRY_USERNAME,
    val registryToken: String
) {
    fun validate() {
        require(registryHost.isNotBlank()) { "Missing registry host." }
        require(registryUsername.isNotBlank()) { "Missing registry username." }
        require(registryToken.isNotBlank()) { "Missing registry token." }
        require(!PackageNameSupport.isPlainHttpRegistryHost(registryHost)) {
            "Fluxzero registry host must use HTTPS when a registry token is sent. " +
                "Use an https:// registry host or the local TLS proxy for end-to-end tests."
        }
    }
}

data class JavaPackageDependency(
    val source: Path,
    val containerPath: String = "/app/libs/${source.fileName}"
) {
    init {
        require(containerPath.startsWith("/")) { "Dependency container path must be absolute: $containerPath." }
        require(containerPath.endsWith(".jar")) { "Dependency container path must point at a JAR: $containerPath." }
    }
}

data class PackagePublishResult(
    val packageReference: String,
    val digest: String
)
