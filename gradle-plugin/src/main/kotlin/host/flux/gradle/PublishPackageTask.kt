package host.flux.gradle

import host.flux.publishing.BaseImageSource
import host.flux.publishing.JavaPackageDependency
import host.flux.publishing.JavaPackagePublishSpec
import host.flux.publishing.JavaPackagePublisher
import host.flux.publishing.PackageNameSupport
import host.flux.publishing.PackagePublisher
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.jar.JarFile
import kotlin.streams.asSequence

@UntrackedTask(because = "Publishes an OCI image to an external registry")
abstract class PublishPackageTask : DefaultTask() {
    @get:Input
    abstract val packageName: Property<String>

    @get:Input
    abstract val packageVersion: Property<String>

    @get:Optional
    @get:Input
    abstract val applicationId: Property<String>

    @get:Optional
    @get:Input
    abstract val mainClass: Property<String>

    @get:Input
    abstract val images: ListProperty<String>

    @get:Input
    abstract val tags: ListProperty<String>

    @get:Input
    abstract val baseImage: Property<String>

    @get:Input
    abstract val baseImageSource: Property<String>

    @get:Input
    abstract val javaToolOptions: Property<String>

    @get:Input
    abstract val labels: MapProperty<String, String>

    @get:Input
    abstract val defaultLabels: MapProperty<String, String>

    @get:Input
    abstract val publishAttempts: Property<Int>

    @get:Input
    abstract val publishRetryDelayMillis: Property<Long>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val classesDirectories: ConfigurableFileCollection

    @get:Classpath
    abstract val runtimeClasspath: ConfigurableFileCollection

    @get:Optional
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val applicationArtifact: RegularFileProperty

    @get:Internal
    internal lateinit var authentications: NamedDomainObjectContainer<RegistryAuthentication>

    @get:Internal
    internal var publisher: PackagePublisher = JavaPackagePublisher()

    @TaskAction
    fun publishPackage() {
        try {
            val resolvedPackageName = packageName.get().trim()
            val resolvedTags = tags.get().map(String::trim).filter(String::isNotBlank)
            val resolvedVersion = resolvedTags.firstOrNull() ?: packageVersion.get().trim()
            val credentials = GradleRegistryAuthenticationSupport.resolve(authentications)
            val resolvedImages = GradlePackageImageSupport.resolve(
                configuredImages = images.get(),
                packageName = resolvedPackageName,
                credentials = credentials
            )
            val stagedClasses = stageApplicationFiles()
            val resolvedMainClass = mainClass.orNull?.trim()?.takeIf(String::isNotBlank)
                ?: mainClassFromManifest(applicationArtifact.orNull?.asFile?.toPath())
                ?: throw GradleException(
                    "Missing application main class. Configure fluxzero.packagePublishing.mainClass."
                )
            val dependencies = runtimeClasspath.files
                .asSequence()
                .filter { it.isFile && it.extension.equals("jar", ignoreCase = true) }
                .map { JavaPackageDependency(it.toPath()) }
                .toList()

            val references = resolvedImages.flatMap { image ->
                (resolvedTags.ifEmpty { listOf(resolvedVersion) }).map { tag -> "$image:$tag" }
            }
            logger.lifecycle("Building Fluxzero Java package ${references.joinToString()}")
            val results = publisher.publish(
                JavaPackagePublishSpec(
                    packageName = resolvedPackageName,
                    packageVersion = resolvedVersion,
                    applicationId = applicationId.orNull?.trim()?.takeIf(String::isNotBlank),
                    mainClass = resolvedMainClass,
                    baseImage = baseImage.get(),
                    baseImageSource = BaseImageSource.parse(baseImageSource.get()),
                    javaToolOptions = javaToolOptions.get(),
                    classesDirectory = stagedClasses,
                    dependencies = dependencies,
                    defaultLabels = defaultLabels.get(),
                    labels = labels.get().mapValues { it.value },
                    images = resolvedImages,
                    tags = resolvedTags,
                    credentials = credentials,
                    toolName = "fluxzero-gradle-plugin",
                    publishAttempts = publishAttempts.get(),
                    publishRetryDelayMillis = publishRetryDelayMillis.get()
                )
            )
            results.forEach { result ->
                logger.lifecycle(
                    "Published Fluxzero package ${result.packageReference} with digest ${result.digest}"
                )
            }
        } catch (exception: GradleException) {
            throw exception
        } catch (exception: Exception) {
            throw GradleException("Failed to publish Fluxzero package: ${exception.message}", exception)
        }
    }

    private fun stageApplicationFiles(): Path {
        val targetRoot = temporaryDir.toPath().resolve("application")
        if (Files.exists(targetRoot)) {
            targetRoot.toFile().deleteRecursively()
        }
        Files.createDirectories(targetRoot)
        val sourceDirectories = classesDirectories.files.filter { it.isDirectory }
        if (sourceDirectories.isEmpty()) {
            throw GradleException("No compiled application output found. Run classes before publishing.")
        }
        sourceDirectories.forEach { sourceDirectory ->
            val sourceRoot = sourceDirectory.toPath()
            Files.walk(sourceRoot).use { paths ->
                paths.asSequence()
                    .filter(Files::isRegularFile)
                    .sortedBy { sourceRoot.relativize(it).joinToString("/") }
                    .forEach { source ->
                        val target = targetRoot.resolve(sourceRoot.relativize(source).toString())
                        Files.createDirectories(target.parent)
                        if (Files.exists(target) &&
                            !Files.readAllBytes(source).contentEquals(Files.readAllBytes(target))) {
                            throw GradleException(
                                "Conflicting application output '${sourceRoot.relativize(source)}' from multiple source sets."
                            )
                        }
                        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
                    }
            }
        }
        return targetRoot
    }

    private fun mainClassFromManifest(artifact: Path?): String? {
        if (artifact == null || !Files.isRegularFile(artifact)) {
            return null
        }
        return JarFile(artifact.toFile()).use { jar ->
            PackageNameSupport.mainClassFromManifest(jar.manifest?.mainAttributes)
        }
    }
}
