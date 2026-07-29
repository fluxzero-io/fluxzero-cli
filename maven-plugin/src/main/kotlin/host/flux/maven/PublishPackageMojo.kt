package host.flux.maven

import host.flux.publishing.BaseImageSource
import host.flux.publishing.JavaPackageDependency
import host.flux.publishing.JavaPackageExtraDirectory
import host.flux.publishing.JavaPackagePlatform
import host.flux.publishing.JavaPackageRegistryCredential
import host.flux.publishing.JavaPackagePublishSpec
import host.flux.publishing.JavaPackagePublisher
import host.flux.publishing.PackageNameSupport
import org.apache.maven.execution.MavenSession
import org.apache.maven.plugin.AbstractMojo
import org.apache.maven.plugin.MojoExecutionException
import org.apache.maven.plugin.MojoFailureException
import org.apache.maven.plugins.annotations.LifecyclePhase
import org.apache.maven.plugins.annotations.Mojo
import org.apache.maven.plugins.annotations.Parameter
import org.apache.maven.plugins.annotations.ResolutionScope
import org.apache.maven.project.MavenProject
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile

/**
 * Builds a Java OCI package from Maven output and publishes it to a Fluxzero registry.
 *
 * The package is assembled locally from deterministic layers:
 * - Maven runtime dependencies
 * - compiled application classes/resources as the top application layer
 *
 * Jib pushes these layers through the OCI/Docker Registry V2 protocol. Existing blobs are discovered by digest and are
 * not uploaded again, so repeated Fluxzero applications can share dependency layers without sending fat JARs.
 */
@Mojo(
    name = "publish-package",
    defaultPhase = LifecyclePhase.DEPLOY,
    requiresDependencyResolution = ResolutionScope.RUNTIME,
    threadSafe = true
)
class PublishPackageMojo : AbstractMojo() {

    @Parameter(defaultValue = "\${project}", readonly = true)
    private lateinit var project: MavenProject

    @Parameter(defaultValue = "\${session}", readonly = true)
    private var session: MavenSession? = null

    /**
     * Image repositories, required for modules that configure packageName. Complete path segments may use placeholders.
     */
    @Parameter
    private var images: List<String> = emptyList()

    /**
     * Optional tags.
     */
    @Parameter
    private var tags: List<String> = emptyList()

    /**
     * Optional host-bound registry authentication. Target registries without a matching configuration use anonymous access.
     */
    @Parameter
    private var authentications: List<Authentication> = emptyList()

    /**
     * Public package name.
     */
    @Parameter
    private var packageName: String? = null

    /**
     * Package version to push. When omitted, a git/time-based tag is generated.
     */
    @Parameter
    private var packageVersion: String? = null

    /**
     * Optional Fluxzero application id associated with this package. Stored as package metadata for future deployment flows.
     */
    @Parameter
    private var applicationId: String? = null

    /**
     * Application main class. When omitted, the plugin reads Start-Class or Main-Class from the project artifact.
     */
    @Parameter(property = "fluxzero.package.mainClass")
    private var mainClass: String? = null

    /**
     * Java runtime base image.
     */
    @Parameter(property = "fluxzero.package.baseImage")
    private var baseImage: String? = null

    /**
     * Where to read the base image from: registry or docker-daemon.
     */
    @Parameter(property = "fluxzero.package.baseImageSource")
    private var baseImageSource: String? = null

    /**
     * Value to write to JAVA_TOOL_OPTIONS. Defaults to the process JAVA_TOOL_OPTIONS or Fluxzero JVM options.
     */
    @Parameter(property = "fluxzero.package.javaToolOptions")
    private var javaToolOptions: String? = null

    /**
     * Target operating-system and architecture pairs. Defaults to linux/amd64.
     */
    @Parameter
    private var platforms: List<Platform> = emptyList()

    /**
     * Additional directories copied into deterministic image layers.
     */
    @Parameter
    private var extraDirectories: List<ExtraDirectory> = emptyList()

    /**
     * Whether standard OCI, Maven, and Fluxzero labels are included before custom label overrides.
     */
    @Parameter(defaultValue = "true")
    private var includeDefaultLabels: Boolean = true

    /**
     * Custom label additions, overrides, and removals.
     */
    @Parameter
    private var labels: List<Label> = emptyList()

    /**
     * Whether to skip building and publishing the package.
     */
    @Parameter(property = "fluxzero.package.skip", defaultValue = "false")
    private var skipPackagePublish: Boolean = false

    /**
     * Maximum publish attempts per image for transient registry failures.
     */
    @Parameter(property = "fluxzero.package.publishAttempts", defaultValue = "10")
    private var publishAttempts: Int = JavaPackagePublishSpec.DEFAULT_PUBLISH_ATTEMPTS

    /**
     * Base delay between publish attempts in milliseconds.
     */
    @Parameter(property = "fluxzero.package.publishRetryDelayMillis", defaultValue = "2000")
    private var publishRetryDelayMillis: Long = JavaPackagePublishSpec.DEFAULT_PUBLISH_RETRY_DELAY_MILLIS

    override fun execute() {
        if (skipPackagePublish) {
            log.info("Skipping Fluxzero package publish")
            return
        }
        if (project.packaging == "pom") {
            log.info("Skipping Fluxzero package publish for pom-packaging project ${project.groupId}:${project.artifactId}")
            return
        }

        val resolvedPackageName = packageName?.takeIf { it.isNotBlank() }
        if (resolvedPackageName == null) {
            log.info(
                "Skipping Fluxzero package publish for ${project.groupId}:${project.artifactId}: no packageName configured"
            )
            return
        }
        val gitInfo = PackageNameSupport.gitInfo(project.basedir.toPath())
        val resolvedTags = resolveTags()
        val resolvedVersion = resolvedTags.first()
        val resolvedApplicationId = applicationId?.takeIf { it.isNotBlank() }
        if (!PackageNameSupport.isValidPackageName(resolvedPackageName)) {
            throw MojoFailureException("Invalid package name '$resolvedPackageName'.")
        }
        if (!PackageNameSupport.isValidTag(resolvedVersion)) {
            throw MojoFailureException("Invalid package version '$resolvedVersion'.")
        }

        val outputDirectory = File(project.build.outputDirectory)
        if (!outputDirectory.isDirectory) {
            throw MojoFailureException("Project output directory does not exist: ${outputDirectory.absolutePath}. Run package before publish-package.")
        }

        val resolvedMainClass = configured("fluxzero.package.mainClass", "FLUXZERO_MAIN_CLASS", mainClass)
            ?: mainClassFromManifest(project.artifact?.file)
            ?: throw MojoFailureException("Missing application main class. Set -Dfluxzero.package.mainClass.")
        val configuredBaseImage = configured("fluxzero.package.baseImage", "FLUXZERO_BASE_IMAGE", baseImage)
        val resolvedBaseImage = configuredBaseImage ?: JavaPackagePublishSpec.DEFAULT_BASE_IMAGE
        val resolvedBaseImageSource = configured("fluxzero.package.baseImageSource", "FLUXZERO_BASE_IMAGE_SOURCE", baseImageSource)
            ?.let(BaseImageSource::parse)
            ?: BaseImageSource.REGISTRY
        if (resolvedBaseImageSource == BaseImageSource.DOCKER_DAEMON && configuredBaseImage == null) {
            throw MojoFailureException(
                "Set fluxzero.package.baseImage or FLUXZERO_BASE_IMAGE when fluxzero.package.baseImageSource is docker-daemon."
            )
        }
        val resolvedJavaToolOptions = configuredValue("fluxzero.package.javaToolOptions", "JAVA_TOOL_OPTIONS", javaToolOptions)
            ?: JavaPackagePublishSpec.DEFAULT_JAVA_TOOL_OPTIONS
        val resolvedPlatforms = resolvePlatforms()
        val resolvedExtraDirectories = resolveExtraDirectories()
        val resolvedLabels = PackagePublishingConfigurationSupport.labels(includeDefaultLabels, labels)

        val resolvedCredentials = resolveAuthentications()
        val resolvedImages = resolveImages(resolvedPackageName, resolvedCredentials)
        val packageReferences = resolvedImages.flatMap { image -> resolvedTags.map { tag -> "$image:$tag" } }.joinToString(", ")
        log.info("Building Fluxzero Java package $packageReferences")

        try {
            val results = JavaPackagePublisher().publish(
                JavaPackagePublishSpec(
                    packageName = resolvedPackageName,
                    packageVersion = resolvedVersion,
                    applicationId = resolvedApplicationId,
                    mainClass = resolvedMainClass,
                    baseImage = resolvedBaseImage,
                    baseImageSource = resolvedBaseImageSource,
                    javaToolOptions = resolvedJavaToolOptions,
                    platforms = resolvedPlatforms,
                    classesDirectory = outputDirectory.toPath(),
                    dependencies = runtimeDependencyPaths().map { JavaPackageDependency(it) },
                    extraDirectories = resolvedExtraDirectories,
                    includeDefaultLabels = resolvedLabels.includeDefaults,
                    defaultLabels = MavenPackageLabels.defaults(project, gitInfo),
                    labels = resolvedLabels.overrides,
                    images = resolvedImages,
                    tags = resolvedTags,
                    credentials = resolvedCredentials,
                    toolName = "fluxzero-maven-plugin",
                    publishAttempts = publishAttempts,
                    publishRetryDelayMillis = publishRetryDelayMillis
                )
            )

            results.forEach { result ->
                log.info("Published Fluxzero package ${result.packageReference} with digest ${result.digest}")
            }
        } catch (e: Exception) {
            throw MojoExecutionException("Failed to publish Fluxzero package $packageReferences", e)
        }
    }

    private fun configured(propertyName: String, environmentVariable: String, configuredValue: String?): String? =
        MavenParameterSupport.firstConfigured(session?.userProperties, propertyName, environmentVariable, configuredValue)

    private fun configuredValue(propertyName: String, environmentVariable: String, configuredValue: String?): String? =
        MavenParameterSupport.firstConfiguredValue(session?.userProperties, propertyName, environmentVariable, configuredValue)

    private fun automaticPackageVersion(): String =
        try {
            PackageNameSupport.automaticPackageVersion(project.basedir.toPath(), allowDirty = true)
        } catch (e: IllegalStateException) {
            throw MojoFailureException(e.message)
        }

    private fun mainClassFromManifest(jarFile: File?): String? {
        if (jarFile == null || !jarFile.isFile) {
            return null
        }
        return JarFile(jarFile).use { jar -> PackageNameSupport.mainClassFromManifest(jar.manifest?.mainAttributes) }
    }

    private fun resolvePlatforms(): List<JavaPackagePlatform> =
        try {
            PackagePublishingConfigurationSupport.platforms(platforms)
        } catch (exception: IllegalArgumentException) {
            throw MojoFailureException(exception.message)
        }

    private fun resolveExtraDirectories(): List<JavaPackageExtraDirectory> =
        try {
            PackagePublishingConfigurationSupport.extraDirectories(extraDirectories, project.basedir.toPath())
        } catch (exception: IllegalArgumentException) {
            throw MojoFailureException(exception.message)
        }

    private fun resolveTags(): List<String> {
        val configuredTags = tags.map { it.trim() }.filter { it.isNotBlank() }
        if (configuredTags.isNotEmpty()) {
            return configuredTags
        }
        return listOf(packageVersion?.takeIf { it.isNotBlank() } ?: automaticPackageVersion())
    }

    private fun resolveImages(
        resolvedPackageName: String,
        credentials: List<JavaPackageRegistryCredential>
    ): List<String> {
        val identities = mutableMapOf<String, String>()
        return try {
            PackageImageSupport.resolve(images, resolvedPackageName, credentials) { credential ->
                identities.getOrPut(credential.host) {
                    RegistryIdentityResolver().resolveOrganisationId(credential)
                }
            }
        } catch (exception: RegistryIdentityException) {
            throw MojoFailureException(exception.message)
        }
    }

    private fun resolveAuthentications(): List<JavaPackageRegistryCredential> = try {
        RegistryAuthenticationSupport.resolve(
            authentications = authentications,
            githubToken = ::resolveGitHubToken
        )
    } catch (exception: IllegalArgumentException) {
        throw MojoFailureException(exception.message)
    } catch (exception: GitHubOidcException) {
        throw MojoFailureException(exception.message)
    }

    private fun resolveGitHubToken(audience: String): String =
        GitHubOidcTokenResolver().resolve(audience)

    private fun runtimeDependencyPaths(): List<Path> =
        MavenRuntimeClasspathOrder.runtimeJars(project.runtimeClasspathElements)
}

internal object PackageImageSupport {
    const val ORGANISATION_ID_PLACEHOLDER = "\${organisationId}"
    const val PACKAGE_NAME_PLACEHOLDER = "\${packageName}"

    fun resolve(
        configuredImages: List<String>,
        packageName: String,
        credentials: List<JavaPackageRegistryCredential>,
        organisationId: (JavaPackageRegistryCredential) -> String
    ): List<String> {
        val images = configuredImages.map { it.trim() }.filter { it.isNotBlank() }
        if (images.isEmpty()) {
            throw MojoFailureException("Configure at least one <image> under <images>.")
        }
        return images.map { configuredImage ->
            validatePlaceholder(configuredImage, PACKAGE_NAME_PLACEHOLDER)
            validatePlaceholder(configuredImage, ORGANISATION_ID_PLACEHOLDER)
            val withPackageName = configuredImage.replace(PACKAGE_NAME_PLACEHOLDER, packageName)
            if (!withPackageName.contains(ORGANISATION_ID_PLACEHOLDER)) {
                return@map withPackageName
            }
            val host = PackageNameSupport.registryAuthority(withPackageName)
            val credential = credentials.singleOrNull { it.host == host }
                ?: throw MojoFailureException(
                    "Image '$configuredImage' uses $ORGANISATION_ID_PLACEHOLDER but has no authentication for registry host '$host'."
                )
            val resolvedOrganisationId = organisationId(credential)
            if (!PackageNameSupport.isValidPackageName(resolvedOrganisationId)) {
                throw MojoFailureException("Registry identity returned an invalid organisationId.")
            }
            withPackageName.replace(ORGANISATION_ID_PLACEHOLDER, resolvedOrganisationId)
        }
    }

    private fun validatePlaceholder(image: String, placeholder: String) {
        if (!image.contains(placeholder)) {
            return
        }
        val pathSegments = image.substringAfter('/', "").split('/')
        if (pathSegments.count { it == placeholder } != 1 || image.countOccurrences(placeholder) != 1) {
            throw MojoFailureException(
                "$placeholder must occur exactly once as a complete image path segment: '$image'."
            )
        }
    }

    private fun String.countOccurrences(value: String): Int = windowed(value.length).count { it == value }
}

internal object MavenRuntimeClasspathOrder {
    fun runtimeJars(classpathElements: Iterable<String>): List<Path> =
        classpathElements
            .map(Path::of)
            .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".jar") }
}
