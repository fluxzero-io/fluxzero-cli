package host.flux.maven

import host.flux.publishing.BaseImageSource
import host.flux.publishing.PackageNameSupport
import host.flux.publishing.JavaPackagePublishSpec
import host.flux.publishing.JavaPackagePublisher
import org.apache.maven.artifact.Artifact
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

    @Parameter(property = "fluxzero.package.registryHost")
    private var registryHost: String? = null

    /**
     * Fluxzero registry token. In CI this can be the registry token returned by the Fluxzero GitHub OIDC exchange.
     */
    @Parameter(property = "fluxzero.package.registryToken")
    private var registryToken: String? = null

    /**
     * Public package name. The registry proxy inserts the Fluxzero team prefix before forwarding to the backing registry.
     */
    @Parameter(property = "fluxzero.package.name")
    private var packageName: String? = null

    /**
     * Package version to push. When omitted, a git/time-based tag is generated.
     */
    @Parameter(property = "fluxzero.package.version")
    private var packageVersion: String? = null

    /**
     * Allows publishing a package from a dirty git worktree. Disabled by default so published packages map to a committed source state.
     */
    @Parameter(property = "fluxzero.package.allowDirty", defaultValue = "false")
    private var allowDirty: Boolean = false

    /**
     * Optional Fluxzero application id associated with this package. Stored as package metadata for future deployment flows.
     */
    @Parameter(property = "fluxzero.package.applicationId")
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

    override fun execute() {
        if (skipPackagePublish) {
            log.info("Skipping Fluxzero package publish")
            return
        }
        if (project.packaging == "pom") {
            log.info("Skipping Fluxzero package publish for pom-packaging project ${project.groupId}:${project.artifactId}")
            return
        }

        val resolvedRegistryHost = configured("fluxzero.package.registryHost", "FLUXZERO_REGISTRY_HOST", registryHost)
            ?: PackageNameSupport.DEFAULT_REGISTRY_HOST
        val resolvedToken = configured("fluxzero.package.registryToken", "FLUXZERO_REGISTRY_TOKEN", registryToken)
        if (resolvedRegistryHost.isNullOrBlank()) {
            throw MojoFailureException("Missing registry host. Set -Dfluxzero.package.registryHost or FLUXZERO_REGISTRY_HOST.")
        }
        if (resolvedToken.isNullOrBlank()) {
            throw MojoFailureException("Missing registry token. Set -Dfluxzero.package.registryToken or FLUXZERO_REGISTRY_TOKEN.")
        }
        if (PackageNameSupport.isPlainHttpRegistryHost(resolvedRegistryHost)) {
            throw MojoFailureException(
                "Fluxzero registry host must use HTTPS when a registry token is sent. " +
                    "Use an https:// registry host or the local registry proxy for end-to-end tests."
            )
        }

        val resolvedPackageName = configured("fluxzero.package.name", "FLUXZERO_PACKAGE_NAME", packageName)
            ?: throw MojoFailureException(
                "Missing package name. Configure <packageName> in the fluxzero-maven-plugin, " +
                    "set -Dfluxzero.package.name, or set FLUXZERO_PACKAGE_NAME."
            )
        val gitInfo = PackageNameSupport.gitInfo(project.basedir.toPath())
        ensureCleanGitWorktree(gitInfo)
        val resolvedVersion = configured("fluxzero.package.version", "FLUXZERO_PACKAGE_VERSION", packageVersion)
            ?.let { markDirtyPackageVersion(it, gitInfo) }
            ?: automaticPackageVersion()
        val resolvedApplicationId = configured("fluxzero.package.applicationId", "FLUXZERO_PACKAGE_ID", applicationId)
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

        val packageReference = PackageNameSupport.packageReference(resolvedRegistryHost, resolvedPackageName, resolvedVersion)
        log.info("Building Fluxzero Java package $packageReference")

        try {
            val result = JavaPackagePublisher().publish(
                JavaPackagePublishSpec(
                    registryHost = resolvedRegistryHost,
                    registryToken = resolvedToken,
                    packageName = resolvedPackageName,
                    packageVersion = resolvedVersion,
                    applicationId = resolvedApplicationId,
                    mainClass = resolvedMainClass,
                    baseImage = resolvedBaseImage,
                    baseImageSource = resolvedBaseImageSource,
                    javaToolOptions = resolvedJavaToolOptions,
                    classesDirectory = outputDirectory.toPath(),
                    releaseDependencies = runtimeArtifacts(snapshot = false).map { it.file.toPath() },
                    snapshotDependencies = runtimeArtifacts(snapshot = true).map { it.file.toPath() },
                    labels = mavenLabels(),
                    toolName = "fluxzero-maven-plugin"
                )
            )

            log.info("Published Fluxzero package ${result.packageReference} with digest ${result.digest}")
        } catch (e: Exception) {
            throw MojoExecutionException("Failed to publish Fluxzero package $packageReference", e)
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

    private fun mavenLabels(): Map<String, String> = mapOf(
        "io.fluxzero.maven.group-id" to project.groupId,
        "io.fluxzero.maven.artifact-id" to project.artifactId,
        "io.fluxzero.maven.version" to project.version
    )

    private fun runtimeArtifacts(snapshot: Boolean): List<Artifact> =
        project.artifacts
            .filter { artifact ->
                artifact.file?.isFile == true &&
                    artifact.type == "jar" &&
                    artifact.scope in setOf(Artifact.SCOPE_COMPILE, Artifact.SCOPE_RUNTIME) &&
                    artifact.isSnapshot == snapshot
            }
            .sortedWith(compareBy<Artifact> { it.groupId }.thenBy { it.artifactId }.thenBy { it.version }.thenBy { it.file.name })
}
