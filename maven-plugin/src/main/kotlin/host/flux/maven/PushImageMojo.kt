package host.flux.maven

import host.flux.publishing.ImageNameSupport
import host.flux.publishing.JavaImagePublishSpec
import host.flux.publishing.JavaImagePublisher
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
 * Builds a Java OCI image from Maven output and pushes it to a Fluxzero registry.
 *
 * The image is assembled locally from deterministic layers:
 * - release dependencies
 * - snapshot dependencies
 * - compiled application classes/resources
 *
 * Jib pushes these layers through the OCI/Docker Registry V2 protocol. Existing blobs are discovered by digest and are
 * not uploaded again, so repeated Fluxzero applications can share dependency layers without sending fat JARs.
 */
@Mojo(
    name = "push-image",
    defaultPhase = LifecyclePhase.DEPLOY,
    requiresDependencyResolution = ResolutionScope.RUNTIME,
    threadSafe = true
)
class PushImageMojo : AbstractMojo() {

    @Parameter(defaultValue = "\${project}", readonly = true)
    private lateinit var project: MavenProject

    @Parameter(defaultValue = "\${session}", readonly = true)
    private var session: MavenSession? = null

    @Parameter(property = "fluxzero.image.registryHost")
    private var registryHost: String? = null

    /**
     * Fluxzero registry token. In CI this can be the registry token returned by the Fluxzero GitHub OIDC exchange.
     */
    @Parameter(property = "fluxzero.image.registryToken")
    private var registryToken: String? = null

    /**
     * Public image name. The registry proxy inserts the Fluxzero team prefix before forwarding to the backing registry.
     */
    @Parameter(property = "fluxzero.image.name")
    private var imageName: String? = null

    /**
     * Image tag to push. When omitted, a git/time-based tag is generated.
     */
    @Parameter(property = "fluxzero.image.version")
    private var imageVersion: String? = null

    /**
     * Allows publishing an image from a dirty git worktree. Disabled by default so pushed images map to a committed source state.
     */
    @Parameter(property = "fluxzero.image.allowDirty", defaultValue = "false")
    private var allowDirty: Boolean = false

    /**
     * Optional Fluxzero application id associated with this image. Stored as image metadata for future deployment flows.
     */
    @Parameter(property = "fluxzero.image.applicationId")
    private var applicationId: String? = null

    /**
     * Application main class. When omitted, the plugin reads Start-Class or Main-Class from the project artifact.
     */
    @Parameter(property = "fluxzero.image.mainClass")
    private var mainClass: String? = null

    /**
     * Java runtime base image.
     */
    @Parameter(property = "fluxzero.image.baseImage")
    private var baseImage: String? = null

    /**
     * Whether to skip building and pushing the image.
     */
    @Parameter(property = "fluxzero.image.skip", defaultValue = "false")
    private var skipImagePush: Boolean = false

    override fun execute() {
        if (skipImagePush) {
            log.info("Skipping Fluxzero image push")
            return
        }
        if (project.packaging == "pom") {
            log.info("Skipping Fluxzero image push for pom-packaging project ${project.groupId}:${project.artifactId}")
            return
        }

        val resolvedRegistryHost = configured("fluxzero.image.registryHost", "FLUXZERO_REGISTRY_HOST", registryHost)
            ?: ImageNameSupport.DEFAULT_REGISTRY_HOST
        val resolvedToken = configured("fluxzero.image.registryToken", "FLUXZERO_REGISTRY_TOKEN", registryToken)
        if (resolvedRegistryHost.isNullOrBlank()) {
            throw MojoFailureException("Missing registry host. Set -Dfluxzero.image.registryHost or FLUXZERO_REGISTRY_HOST.")
        }
        if (resolvedToken.isNullOrBlank()) {
            throw MojoFailureException("Missing registry token. Set -Dfluxzero.image.registryToken or FLUXZERO_REGISTRY_TOKEN.")
        }
        if (ImageNameSupport.isPlainHttpRegistryHost(resolvedRegistryHost)) {
            throw MojoFailureException(
                "Fluxzero image registry host must use HTTPS when a registry token is sent. " +
                    "Use an https:// registry host or the local registry proxy for end-to-end tests."
            )
        }

        val resolvedImageName = configured("fluxzero.image.name", "FLUXZERO_IMAGE_NAME", imageName)
            ?: throw MojoFailureException(
                "Missing image name. Configure <imageName> in the fluxzero-maven-plugin, " +
                    "set -Dfluxzero.image.name, or set FLUXZERO_IMAGE_NAME."
            )
        val gitInfo = ImageNameSupport.gitInfo(project.basedir.toPath())
        ensureCleanGitWorktree(gitInfo)
        val resolvedVersion = configured("fluxzero.image.version", "FLUXZERO_IMAGE_VERSION", imageVersion)
            ?.let { markDirtyImageVersion(it, gitInfo) }
            ?: automaticImageVersion()
        val resolvedApplicationId = configured("fluxzero.image.applicationId", "FLUXZERO_APPLICATION_ID", applicationId)
        if (!ImageNameSupport.isValidImageName(resolvedImageName)) {
            throw MojoFailureException("Invalid image name '$resolvedImageName'.")
        }
        if (!ImageNameSupport.isValidTag(resolvedVersion)) {
            throw MojoFailureException("Invalid image version '$resolvedVersion'.")
        }

        val outputDirectory = File(project.build.outputDirectory)
        if (!outputDirectory.isDirectory) {
            throw MojoFailureException("Project output directory does not exist: ${outputDirectory.absolutePath}. Run package before push-image.")
        }

        val resolvedMainClass = configured("fluxzero.image.mainClass", "FLUXZERO_IMAGE_MAIN_CLASS", mainClass)
            ?: mainClassFromManifest(project.artifact?.file)
            ?: throw MojoFailureException("Missing application main class. Set -Dfluxzero.image.mainClass.")
        val resolvedBaseImage = configured("fluxzero.image.baseImage", "FLUXZERO_IMAGE_BASE_IMAGE", baseImage)
            ?: JavaImagePublishSpec.DEFAULT_BASE_IMAGE

        val imageReference = ImageNameSupport.imageReference(resolvedRegistryHost, resolvedImageName, resolvedVersion)
        log.info("Building Fluxzero Java image $imageReference")

        try {
            val result = JavaImagePublisher().publish(
                JavaImagePublishSpec(
                    registryHost = resolvedRegistryHost,
                    registryToken = resolvedToken,
                    imageName = resolvedImageName,
                    imageVersion = resolvedVersion,
                    applicationId = resolvedApplicationId,
                    mainClass = resolvedMainClass,
                    baseImage = resolvedBaseImage,
                    classesDirectory = outputDirectory.toPath(),
                    releaseDependencies = runtimeArtifacts(snapshot = false).map { it.file.toPath() },
                    snapshotDependencies = runtimeArtifacts(snapshot = true).map { it.file.toPath() },
                    labels = mavenLabels(),
                    toolName = "fluxzero-maven-plugin"
                )
            )

            log.info("Pushed Fluxzero image ${result.imageReference} with digest ${result.digest}")
        } catch (e: Exception) {
            throw MojoExecutionException("Failed to build and push Fluxzero image $imageReference", e)
        }
    }

    private fun ensureCleanGitWorktree(gitInfo: ImageNameSupport.GitInfo?) {
        try {
            ImageNameSupport.ensureCleanGitWorktree(gitInfo, allowDirty)
        } catch (e: IllegalStateException) {
            throw MojoFailureException(e.message)
        }
    }

    private fun configured(propertyName: String, environmentVariable: String, configuredValue: String?): String? =
        MavenParameterSupport.firstConfigured(session?.userProperties, propertyName, environmentVariable, configuredValue)

    private fun markDirtyImageVersion(imageVersion: String, gitInfo: ImageNameSupport.GitInfo?): String =
        try {
            ImageNameSupport.markDirtyImageVersion(imageVersion, gitInfo, allowDirty)
        } catch (e: IllegalStateException) {
            throw MojoFailureException(e.message)
        }

    private fun automaticImageVersion(): String =
        try {
            ImageNameSupport.automaticImageVersion(project.basedir.toPath(), allowDirty = allowDirty)
        } catch (e: IllegalStateException) {
            throw MojoFailureException(e.message)
        }

    private fun mainClassFromManifest(jarFile: File?): String? {
        if (jarFile == null || !jarFile.isFile) {
            return null
        }
        return JarFile(jarFile).use { jar -> ImageNameSupport.mainClassFromManifest(jar.manifest?.mainAttributes) }
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
