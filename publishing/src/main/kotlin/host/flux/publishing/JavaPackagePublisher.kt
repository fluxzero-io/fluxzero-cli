package host.flux.publishing

import com.google.cloud.tools.jib.api.Containerizer
import com.google.cloud.tools.jib.api.DockerDaemonImage
import com.google.cloud.tools.jib.api.Jib
import com.google.cloud.tools.jib.api.JibContainerBuilder
import com.google.cloud.tools.jib.api.RegistryImage
import com.google.cloud.tools.jib.api.buildplan.AbsoluteUnixPath
import com.google.cloud.tools.jib.api.buildplan.ContainerBuildPlan
import com.google.cloud.tools.jib.api.buildplan.FileEntriesLayer
import com.google.cloud.tools.jib.api.buildplan.Platform
import java.io.EOFException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.streams.asSequence

fun interface PackagePublisher {
    fun publish(spec: JavaPackagePublishSpec): List<PackagePublishResult>
}

class JavaPackagePublisher : PackagePublisher {
    override fun publish(spec: JavaPackagePublishSpec): List<PackagePublishResult> {
        spec.validate()
        JibHttpTimeout.configureDefault()

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
            val builder = createContainerBuilder(spec)
            val targetImage = RegistryImage.named(publishTarget.primaryReference.reference)
            spec.credentialFor(publishTarget.image)?.let { credential ->
                targetImage.addCredential(credential.username, credential.password)
            }
            val containerizer = Containerizer.to(targetImage)
                .setToolName(spec.toolName)
            publishTarget.additionalTags.forEach { tag ->
                containerizer.withAdditionalTag(tag)
            }

            try {
                val container = builder.containerize(containerizer)
                return publishTarget.references.map { packageReference ->
                    PackagePublishResult(packageReference.reference, container.digest.toString())
                }
            } catch (exception: Exception) {
                val willRetry = attempt < spec.publishAttempts && exception.isRetriableRegistryPublishFailure()
                if (!willRetry) {
                    throw exception
                }
                sleepBeforeRetry(retryDelayMillis(spec, attempt), exception)
                attempt++
            }
        }
    }

    internal fun buildPlan(spec: JavaPackagePublishSpec): ContainerBuildPlan {
        spec.validate()
        return createContainerBuilder(spec).toContainerBuildPlan()
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
            .setPlatforms(
                spec.platforms.mapTo(linkedSetOf()) { platform ->
                    Platform(platform.architecture, platform.os)
                }
            )
            .setCreationTime(JavaPackagePublishSpec.REPRODUCIBLE_CONTAINER_TIMESTAMP)
            .setWorkingDirectory(AbsoluteUnixPath.get("/app"))
            .setEntrypoint("java", "-cp", classpath(dependencies), spec.mainClass)

        builder.addEnvironmentVariable("JAVA_TOOL_OPTIONS", spec.javaToolOptions)

        spec.resolvedLabels().forEach { (name, value) ->
            builder.addLabel(name, value)
        }

        addDependencyLayer(builder, "dependencies", dependencies)
        addExtraDirectoryLayers(builder, spec.extraDirectories)
        addApplicationLayer(builder, spec.classesDirectory)
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

    private fun addExtraDirectoryLayers(
        builder: JibContainerBuilder,
        extraDirectories: List<JavaPackageExtraDirectory>
    ) {
        extraDirectories.forEachIndexed { index, extraDirectory ->
            val files = Files.walk(extraDirectory.source).use { paths ->
                paths.asSequence()
                    .filter { Files.isRegularFile(it) }
                    .sortedBy { normalizedRelativePath(extraDirectory.source, it) }
                    .toList()
            }
            if (files.isEmpty()) {
                return@forEachIndexed
            }

            val targetRoot = AbsoluteUnixPath.get(extraDirectory.normalizedContainerPath)
            val layerBuilder = FileEntriesLayer.builder().setName("extra-directory-${index + 1}")
            files.forEach { file ->
                layerBuilder.addEntry(
                    file,
                    targetRoot.resolve(normalizedRelativePath(extraDirectory.source, file)),
                    JavaPackagePublishSpec.REPRODUCIBLE_FILE_TIMESTAMP
                )
            }
            builder.addFileEntriesLayer(layerBuilder.build())
        }
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

internal object JibHttpTimeout {
    const val PROPERTY = "jib.httpTimeout"
    const val DEFAULT_MILLIS = 60_000

    fun configureDefault() {
        val properties = System.getProperties()
        synchronized(properties) {
            if (!properties.containsKey(PROPERTY)) {
                properties.setProperty(PROPERTY, DEFAULT_MILLIS.toString())
            }
        }
    }
}

internal fun Throwable.isRetriableRegistryPublishFailure(): Boolean =
    generateSequence(this) { it.cause }.any { throwable ->
        throwable is SocketTimeoutException ||
            throwable is SocketException ||
            throwable is EOFException ||
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
    val packageName: String,
    val packageVersion: String,
    val mainClass: String,
    val classesDirectory: Path,
    val images: List<String>,
    val credentials: List<JavaPackageRegistryCredential> = emptyList(),
    val applicationId: String? = null,
    val baseImage: String = DEFAULT_BASE_IMAGE,
    val baseImageSource: BaseImageSource = BaseImageSource.REGISTRY,
    val javaToolOptions: String = DEFAULT_JAVA_TOOL_OPTIONS,
    val platforms: List<JavaPackagePlatform> = listOf(JavaPackagePlatform.DEFAULT),
    val dependencies: List<JavaPackageDependency> = emptyList(),
    val extraDirectories: List<JavaPackageExtraDirectory> = emptyList(),
    val includeDefaultLabels: Boolean = true,
    val defaultLabels: Map<String, String> = emptyMap(),
    val labels: Map<String, String?> = emptyMap(),
    val tags: List<String> = emptyList(),
    val toolName: String = "fluxzero-publishing",
    val publishAttempts: Int = DEFAULT_PUBLISH_ATTEMPTS,
    val publishRetryDelayMillis: Long = DEFAULT_PUBLISH_RETRY_DELAY_MILLIS
) {
    companion object {
        val REPRODUCIBLE_CONTAINER_TIMESTAMP: Instant = Instant.EPOCH
        val REPRODUCIBLE_FILE_TIMESTAMP: Instant = Instant.EPOCH

        const val DEFAULT_PUBLISH_ATTEMPTS = 10
        const val DEFAULT_PUBLISH_RETRY_DELAY_MILLIS = 2000L

        const val DEFAULT_BASE_IMAGE =
            "gcr.io/distroless/java25-debian13:nonroot@sha256:f25ab728deeafec63d7176a473536f4f4347d42db7e24b3bb0fb7b05ff84d248"

        const val DEFAULT_JAVA_TOOL_OPTIONS =
            "-XX:MaxRAMPercentage=75.0 -Djava.security.egd=file:/dev/./urandom -XX:+ExitOnOutOfMemoryError -XX:SoftRefLRUPolicyMSPerMB=2500"
    }

    fun validate() {
        require(PackageNameSupport.isValidPackageName(packageName)) { "Invalid package name '$packageName'." }
        require(PackageNameSupport.isValidTag(packageVersion)) { "Invalid package version '$packageVersion'." }
        require(mainClass.isNotBlank()) { "Missing application main class." }
        require(baseImage.isNotBlank()) { "Missing Java runtime base image." }
        require(platforms.isNotEmpty()) { "Configure at least one package platform." }
        platforms.forEach { it.validate() }
        require(platforms.distinct().size == platforms.size) { "Package platforms must be unique." }
        require(images.isNotEmpty()) { "Configure at least one package image." }
        require(images.distinct().size == images.size) { "Package images must be unique." }
        require(resolvedTags().distinct().size == resolvedTags().size) { "Package tags must be unique." }
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
        extraDirectories.forEach { extraDirectory ->
            require(Files.isDirectory(extraDirectory.source)) {
                "Extra directory does not exist: ${extraDirectory.source.toAbsolutePath()}."
            }
        }
        validateExtraDirectoryTargets()
        defaultLabels.forEach { (key, value) ->
            require(key.isNotBlank()) { "Default package label keys must not be blank." }
            require(value.isNotBlank()) { "Default package label '$key' must not be blank." }
        }
        labels.keys.forEach { key ->
            require(key.isNotBlank()) { "Package label keys must not be blank." }
        }
        credentials.forEach { it.validate() }
        val duplicateCredentialHosts = credentials
            .groupBy { it.host }
            .filterValues { it.size > 1 }
            .keys
        require(duplicateCredentialHosts.isEmpty()) {
            "Configure exactly one registry credential per host. Duplicate: ${duplicateCredentialHosts.sorted().joinToString()}."
        }
        val references = packageReferences()
        val targetHosts = references.map { PackageNameSupport.registryAuthority(it.image) }.toSet()
        val unusedCredentialHosts = credentials.map { it.host }.toSet() - targetHosts
        require(unusedCredentialHosts.isEmpty()) {
            "Registry credentials must match a package image. Unused: ${unusedCredentialHosts.sorted().joinToString()}."
        }
    }

    fun packageReferences(): List<JavaPackageReference> =
        publishTargets().flatMap { it.references }

    internal fun publishTargets(): List<JavaPackagePublishTarget> {
        val tags = resolvedTags()
        return images.map { image ->
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

    fun credentialFor(image: String): JavaPackageRegistryCredential? {
        val targetHost = PackageNameSupport.registryAuthority(image)
        return credentials.firstOrNull { credential ->
            credential.host == targetHost
        }
    }

    private fun resolvedTags(): List<String> =
        tags.takeIf { it.isNotEmpty() } ?: listOf(packageVersion)

    internal fun orderedDependencies(): List<JavaPackageDependency> = dependencies

    internal fun resolvedLabels(): Map<String, String> = buildMap {
        if (includeDefaultLabels) {
            put("org.opencontainers.image.title", packageName)
            put("org.opencontainers.image.version", packageVersion)
            put("io.fluxzero.package.metadata-version", "1")
            applicationId?.takeIf { it.isNotBlank() }?.let { put("io.fluxzero.application-id", it) }
            putAll(defaultLabels)
        }
        labels.forEach { (key, value) ->
            if (value == null) {
                remove(key)
            } else {
                put(key, value)
            }
        }
    }

    private fun validateExtraDirectoryTargets() {
        val targets = extraDirectories.map { it.normalizedContainerPath }
        val reservedTargets = listOf("/app/classes", "/app/libs")
        targets.forEach { target ->
            require(reservedTargets.none { reserved -> containerPathsOverlap(target, reserved) }) {
                "Extra directory target '$target' overlaps a managed package path."
            }
        }
        targets.forEachIndexed { index, target ->
            require(targets.drop(index + 1).none { other -> containerPathsOverlap(target, other) }) {
                "Extra directory targets must not overlap: '$target'."
            }
        }
    }
}

data class JavaPackagePlatform(
    val os: String,
    val architecture: String
) {
    companion object {
        val DEFAULT = JavaPackagePlatform(os = "linux", architecture = "amd64")
    }

    fun validate() {
        require(os == "linux") { "Unsupported package platform OS '$os'. Only linux is supported." }
        require(architecturePattern.matches(architecture)) {
            "Invalid package platform architecture '$architecture'."
        }
    }
}

data class JavaPackageExtraDirectory(
    val source: Path,
    val containerPath: String
) {
    val normalizedContainerPath: String = normalizeContainerDirectory(containerPath)
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
    val host: String,
    val username: String = "",
    val password: String
) {
    fun validate() {
        require(PackageNameSupport.isValidRegistryHost(host)) {
            "Invalid registry credential host '$host'. Configure a lowercase host with an optional port, without a scheme or path."
        }
        require(password.isNotBlank()) { "Missing registry password or token." }
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

private val architecturePattern = Regex("[a-z0-9][a-z0-9_-]*")

private fun normalizeContainerDirectory(path: String): String {
    require(path.startsWith("/")) { "Extra directory target must be absolute: $path." }
    require('\\' !in path) { "Extra directory target must use Unix separators: $path." }
    val segments = path.split('/').filter { it.isNotEmpty() }
    require(segments.isNotEmpty()) { "Extra directory target must not be the container root." }
    require(segments.none { it == "." || it == ".." }) {
        "Extra directory target must not contain '.' or '..': $path."
    }
    return "/${segments.joinToString("/")}"
}

private fun containerPathsOverlap(first: String, second: String): Boolean =
    first == second || first.startsWith("$second/") || second.startsWith("$first/")
