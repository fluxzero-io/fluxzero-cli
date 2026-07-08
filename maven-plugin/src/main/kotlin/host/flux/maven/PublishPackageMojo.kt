package host.flux.maven

import host.flux.publishing.BaseImageSource
import host.flux.publishing.JavaPackageDependency
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
 * - release dependencies
 * - snapshot dependencies
 * - compiled application classes/resources
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

    @Parameter
    private var registryHost: String? = null

    @Parameter
    private var registryUsername: String? = null

    /**
     * Fluxzero registry token. In CI this can be the registry token returned by the Fluxzero GitHub OIDC exchange.
     */
    @Parameter
    private var registryToken: String? = null

    /**
     * Optional image repositories.
     */
    @Parameter
    private var images: List<String> = emptyList()

    /**
     * Optional tags.
     */
    @Parameter
    private var tags: List<String> = emptyList()

    /**
     * Optional registry credentials. When omitted, the legacy single-target registry settings are used.
     */
    @Parameter
    private var credentials: List<RegistryCredentialConfiguration> = emptyList()

    /**
     * Public package name.
     */
    @Parameter
    private var packageName: String? = null

    /**
     * Fluxzero team id used as the first registry path segment.
     */
    @Parameter
    private var teamId: String? = null

    /**
     * Package version to push. When omitted, a git/time-based tag is generated.
     */
    @Parameter
    private var packageVersion: String? = null

    /**
     * Allows publishing a package from a dirty git worktree. Disabled by default so published packages map to a committed source state.
     */
    @Parameter(property = "fluxzero.package.allowDirty", defaultValue = "false")
    private var allowDirty: Boolean = false

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
     * Whether to skip building and publishing the package.
     */
    @Parameter(property = "fluxzero.package.skip", defaultValue = "false")
    private var skipPackagePublish: Boolean = false

    /**
     * Maximum publish attempts per image for transient registry blob-upload failures.
     */
    @Parameter(property = "fluxzero.package.publishAttempts", defaultValue = "3")
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

        val resolvedRegistryHost = registryHost?.takeIf { it.isNotBlank() }
            ?: PackageNameSupport.DEFAULT_REGISTRY_HOST
        val resolvedRegistryUsername = registryUsername?.takeIf { it.isNotBlank() }
            ?: JavaPackagePublishSpec.DEFAULT_REGISTRY_USERNAME
        val resolvedToken = registryToken?.takeIf { it.isNotBlank() }
        if (resolvedRegistryHost.isNullOrBlank()) {
            throw MojoFailureException("Missing registry host. Configure <registryHost> in the fluxzero-maven-plugin.")
        }
        if (resolvedRegistryUsername.isBlank()) {
            throw MojoFailureException("Missing registry username. Configure <registryUsername> in the fluxzero-maven-plugin.")
        }
        if (resolvedToken.isNullOrBlank() && credentials.isEmpty()) {
            throw MojoFailureException(
                "Missing registry token. Configure <registryToken>, for example with Maven interpolation like " +
                    "<registryToken>\${env.FLUXZERO_REGISTRY_TOKEN}</registryToken>."
            )
        }
        if (PackageNameSupport.isPlainHttpRegistryHost(resolvedRegistryHost)) {
            throw MojoFailureException(
                "Fluxzero registry host must use HTTPS when a registry token is sent. " +
                    "Use an https:// registry host or the local registry proxy for end-to-end tests."
            )
        }

        val resolvedPackageName = packageName?.takeIf { it.isNotBlank() }
            ?: throw MojoFailureException(
                "Missing package name. Configure <packageName> in the fluxzero-maven-plugin."
            )
        val resolvedTeamId = teamId?.takeIf { it.isNotBlank() }
        val gitInfo = PackageNameSupport.gitInfo(project.basedir.toPath())
        ensureCleanGitWorktree(gitInfo)
        val resolvedTags = resolveTags(gitInfo)
        val resolvedVersion = resolvedTags.first()
        val resolvedApplicationId = applicationId?.takeIf { it.isNotBlank() }
        if (!PackageNameSupport.isValidPackageName(resolvedPackageName)) {
            throw MojoFailureException("Invalid package name '$resolvedPackageName'.")
        }
        if (resolvedTeamId != null && !PackageNameSupport.isValidTeamId(resolvedTeamId)) {
            throw MojoFailureException("Invalid team id '$resolvedTeamId'.")
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

        val resolvedImages = resolveImages(
            defaultRegistryHost = resolvedRegistryHost,
            defaultPackageName = resolvedPackageName
        )
        val resolvedCredentials = resolveCredentials(
            defaultRegistryHost = resolvedRegistryHost,
            defaultRegistryUsername = resolvedRegistryUsername,
            defaultRegistryToken = resolvedToken
        )
        val packageReferences = resolvedImages.flatMap { image -> resolvedTags.map { tag -> "$image:$tag" } }.joinToString(", ")
        log.info("Building Fluxzero Java package $packageReferences")

        try {
            val results = JavaPackagePublisher().publish(
                JavaPackagePublishSpec(
                    registryHost = resolvedRegistryHost,
                    registryUsername = resolvedRegistryUsername,
                    registryToken = resolvedToken,
                    teamId = resolvedTeamId,
                    packageName = resolvedPackageName,
                    packageVersion = resolvedVersion,
                    applicationId = resolvedApplicationId,
                    mainClass = resolvedMainClass,
                    baseImage = resolvedBaseImage,
                    baseImageSource = resolvedBaseImageSource,
                    javaToolOptions = resolvedJavaToolOptions,
                    classesDirectory = outputDirectory.toPath(),
                    dependencies = runtimeDependencyPaths().map { JavaPackageDependency(it) },
                    labels = mavenLabels(),
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

    private fun ensureCleanGitWorktree(gitInfo: PackageNameSupport.GitInfo?) {
        try {
            PackageNameSupport.ensureCleanGitWorktree(gitInfo, allowDirty)
        } catch (e: IllegalStateException) {
            throw MojoFailureException(e.message)
        }
    }

    private fun configured(propertyName: String, environmentVariable: String, configuredValue: String?): String? =
        MavenParameterSupport.firstConfigured(session?.userProperties, propertyName, environmentVariable, configuredValue)

    private fun configuredValue(propertyName: String, environmentVariable: String, configuredValue: String?): String? =
        MavenParameterSupport.firstConfiguredValue(session?.userProperties, propertyName, environmentVariable, configuredValue)

    private fun markDirtyPackageVersion(packageVersion: String, gitInfo: PackageNameSupport.GitInfo?): String =
        try {
            PackageNameSupport.markDirtyPackageVersion(packageVersion, gitInfo, allowDirty)
        } catch (e: IllegalStateException) {
            throw MojoFailureException(e.message)
        }

    private fun automaticPackageVersion(): String =
        try {
            PackageNameSupport.automaticPackageVersion(project.basedir.toPath(), allowDirty = allowDirty)
        } catch (e: IllegalStateException) {
            throw MojoFailureException(e.message)
        }

    private fun mainClassFromManifest(jarFile: File?): String? {
        if (jarFile == null || !jarFile.isFile) {
            return null
        }
        return JarFile(jarFile).use { jar -> PackageNameSupport.mainClassFromManifest(jar.manifest?.mainAttributes) }
    }

    private fun mavenLabels(): Map<String, String> = buildMap {
        put("io.fluxzero.maven.group-id", project.groupId)
        put("io.fluxzero.maven.artifact-id", project.artifactId)
        put("io.fluxzero.maven.version", project.version)
        githubSourceRepository()?.let { put("org.opencontainers.image.source", it) }
    }

    private fun githubSourceRepository(): String? {
        val repository = System.getenv("GITHUB_REPOSITORY")?.takeIf { it.isNotBlank() } ?: return null
        val serverUrl = System.getenv("GITHUB_SERVER_URL")?.takeIf { it.isNotBlank() } ?: "https://github.com"
        return "${serverUrl.trimEnd('/')}/$repository"
    }

    private fun resolveTags(gitInfo: PackageNameSupport.GitInfo?): List<String> {
        val configuredTags = tags.map { it.trim() }.filter { it.isNotBlank() }
        if (configuredTags.isNotEmpty()) {
            return configuredTags.map { markDirtyPackageVersion(it, gitInfo) }
        }
        return listOf(
            packageVersion?.takeIf { it.isNotBlank() }
                ?.let { markDirtyPackageVersion(it, gitInfo) }
                ?: automaticPackageVersion()
        )
    }

    private fun resolveImages(defaultRegistryHost: String, defaultPackageName: String): List<String> {
        val configuredImages = images.map { it.trim() }.filter { it.isNotBlank() }
        return configuredImages.ifEmpty {
            listOf(
                PackageNameSupport.packageRepository(
                    defaultRegistryHost,
                    teamId?.takeIf { it.isNotBlank() },
                    defaultPackageName
                )
            )
        }
    }

    private fun resolveCredentials(
        defaultRegistryHost: String,
        defaultRegistryUsername: String,
        defaultRegistryToken: String?
    ): List<JavaPackageRegistryCredential> {
        if (credentials.isEmpty()) {
            return listOf(
                JavaPackageRegistryCredential(
                    registryHost = defaultRegistryHost,
                    registryUsername = defaultRegistryUsername,
                    registryToken = defaultRegistryToken.orEmpty()
                )
            )
        }
        return credentials.mapIndexed { index, credential ->
            val registryHost = credential.registryHost?.takeIf { it.isNotBlank() }
                ?: defaultRegistryHost
            val registryUsername = credential.registryUsername?.takeIf { it.isNotBlank() }
                ?: defaultRegistryUsername
            val registryToken = credential.registryToken?.takeIf { it.isNotBlank() }
                ?: defaultRegistryToken
            if (registryToken.isNullOrBlank()) {
                throw MojoFailureException(
                    "Missing registry token for registry credential ${index + 1}. " +
                        "Configure <registryToken>, for example with Maven interpolation like " +
                        "<registryToken>\${env.FLUXZERO_REGISTRY_TOKEN}</registryToken>."
                )
            }
            JavaPackageRegistryCredential(
                registryHost = registryHost,
                registryUsername = registryUsername,
                registryToken = registryToken
            )
        }
    }

    private fun runtimeDependencyPaths(): List<Path> =
        MavenRuntimeClasspathOrder.runtimeJars(project.runtimeClasspathElements)
}

class RegistryCredentialConfiguration {
    var registryHost: String? = null
    var registryUsername: String? = null
    var registryToken: String? = null
}

internal object MavenRuntimeClasspathOrder {
    fun runtimeJars(classpathElements: Iterable<String>): List<Path> =
        classpathElements
            .map(Path::of)
            .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".jar") }
}
