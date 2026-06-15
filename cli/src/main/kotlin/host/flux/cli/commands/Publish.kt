package host.flux.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import host.flux.publishing.BaseImageSource
import host.flux.publishing.PackageNameSupport
import host.flux.publishing.PackagePublisher
import host.flux.publishing.PackagePublishResult
import host.flux.publishing.JavaPackagePublishSpec
import host.flux.publishing.JavaPackagePublisher
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Clock
import java.util.jar.JarFile
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.streams.asSequence
import org.w3c.dom.Element

class Publish(
    private val publisher: PackagePublisher = JavaPackagePublisher(),
    private val processRunner: ProcessRunner = DefaultProcessRunner()
) : CliktCommand() {

    override fun help(context: Context): String =
        "Build and publish a Fluxzero Java application package"

    private val projectDir by option(
        "--project-dir",
        "--dir",
        help = "Project directory. Defaults to the current working directory."
    )
        .path(mustExist = true, canBeFile = false, canBeDir = true, mustBeWritable = true)
        .default(Paths.get(""))

    private val registryHost by option(
        "--registry-host",
        help = "Fluxzero registry host. Defaults to registry.fluxzero.io."
    )

    private val registryToken by option(
        "--registry-token",
        help = "Fluxzero registry token. Can also be set with FLUXZERO_REGISTRY_TOKEN."
    )

    private val packageName by option("--package-name", help = "Package name. Required unless FLUXZERO_PACKAGE_NAME is set.")

    private val packageVersion by option("--package-version", help = "Package version. Defaults to a generated git/time-based tag.")

    private val allowDirty by option(
        "--allow-dirty",
        help = "Allow publishing from a dirty git worktree."
    ).flag(default = false)

    private val applicationId by option(
        "--application-id",
        help = "Optional Fluxzero application id stored as package metadata."
    )

    private val mainClass by option(
        "--main-class",
        help = "Application main class. Defaults to Start-Class or Main-Class from the built artifact manifest."
    )

    private val baseImage by option("--base-image", help = "Java runtime base image override.")

    private val baseImageSource by option(
        "--base-image-source",
        help = "Where to read the base image from: registry or docker-daemon."
    )

    private val javaToolOptions by option(
        "--java-tool-options",
        help = "Value for JAVA_TOOL_OPTIONS. Defaults to the process JAVA_TOOL_OPTIONS or Fluxzero JVM options."
    )

    private val skipBuild by option(
        "--skip-build",
        help = "Do not run Maven before publishing. Expects target/classes and target/fluxzero-dependencies."
    ).flag(default = false)

    override fun run() {
        val root = projectDir.toAbsolutePath().normalize()
        val pom = root.resolve("pom.xml")
        if (!Files.isRegularFile(pom)) {
            throw IllegalStateException("No pom.xml found in $root. CLI publish currently supports Maven Java projects.")
        }

        val project = MavenPomReader.read(pom)
        val dependenciesDirectory = root.resolve("target/fluxzero-dependencies")
        if (!skipBuild) {
            echo("Building Maven project and collecting runtime dependencies...")
            processRunner.run(
                mavenCommand(root) + listOf(
                    "-B",
                    "package",
                    "dependency:copy-dependencies",
                    "-DincludeScope=runtime",
                    "-DoutputDirectory=${dependenciesDirectory.toAbsolutePath()}"
                ),
                root
            )
        }

        val classesDirectory = root.resolve("target/classes")
        val resolvedRegistryHost = PackageNameSupport.firstConfigured(registryHost, "FLUXZERO_REGISTRY_HOST")
            ?: PackageNameSupport.DEFAULT_REGISTRY_HOST
        val resolvedRegistryToken = PackageNameSupport.firstConfigured(registryToken, "FLUXZERO_REGISTRY_TOKEN")
            ?: throw IllegalStateException("Missing registry token. Set --registry-token or FLUXZERO_REGISTRY_TOKEN.")
        val resolvedPackageName = PackageNameSupport.firstConfigured(packageName, "FLUXZERO_PACKAGE_NAME")
            ?: throw IllegalStateException("Missing package name. Set --package-name or FLUXZERO_PACKAGE_NAME.")
        val gitInfo = PackageNameSupport.gitInfo(root)
        PackageNameSupport.ensureCleanGitWorktree(gitInfo, allowDirty)
        val resolvedPackageVersion = PackageNameSupport.firstConfigured(packageVersion, "FLUXZERO_PACKAGE_VERSION")
            ?.let { PackageNameSupport.markDirtyPackageVersion(it, gitInfo, allowDirty) }
            ?: PackageNameSupport.automaticPackageVersion(Clock.systemUTC(), gitInfo, allowDirty = allowDirty)
        val resolvedApplicationId = PackageNameSupport.firstConfigured(applicationId, "FLUXZERO_PACKAGE_ID")
        val resolvedMainClass = PackageNameSupport.firstConfigured(mainClass, "FLUXZERO_MAIN_CLASS")
            ?: mainClassFromBuiltArtifact(root.resolve("target"), project)
            ?: throw IllegalStateException("Missing application main class. Set --main-class or FLUXZERO_MAIN_CLASS.")
        val configuredBaseImage = PackageNameSupport.firstConfigured(baseImage, "FLUXZERO_BASE_IMAGE")
        val resolvedBaseImage = configuredBaseImage ?: JavaPackagePublishSpec.DEFAULT_BASE_IMAGE
        val resolvedBaseImageSource = PackageNameSupport.firstConfigured(baseImageSource, "FLUXZERO_BASE_IMAGE_SOURCE")
            ?.let(BaseImageSource::parse)
            ?: BaseImageSource.REGISTRY
        if (resolvedBaseImageSource == BaseImageSource.DOCKER_DAEMON && configuredBaseImage == null) {
            throw IllegalStateException("Set --base-image or FLUXZERO_BASE_IMAGE when --base-image-source is docker-daemon.")
        }
        val resolvedJavaToolOptions = PackageNameSupport.firstConfiguredValue(javaToolOptions, "JAVA_TOOL_OPTIONS")
            ?: JavaPackagePublishSpec.DEFAULT_JAVA_TOOL_OPTIONS

        val spec = JavaPackagePublishSpec(
            registryHost = resolvedRegistryHost,
            registryToken = resolvedRegistryToken,
            packageName = resolvedPackageName,
            packageVersion = resolvedPackageVersion,
            applicationId = resolvedApplicationId,
            mainClass = resolvedMainClass,
            baseImage = resolvedBaseImage,
            baseImageSource = resolvedBaseImageSource,
            javaToolOptions = resolvedJavaToolOptions,
            classesDirectory = classesDirectory,
            releaseDependencies = runtimeDependencies(dependenciesDirectory, snapshot = false),
            snapshotDependencies = runtimeDependencies(dependenciesDirectory, snapshot = true),
            labels = mapOf(
                "io.fluxzero.maven.group-id" to project.groupId,
                "io.fluxzero.maven.artifact-id" to project.artifactId,
                "io.fluxzero.maven.version" to project.version
            ),
            toolName = "fluxzero-cli"
        )
        spec.validate()

        echo("Publishing ${PackageNameSupport.packageReference(resolvedRegistryHost, resolvedPackageName, resolvedPackageVersion)}...")
        val result = publisher.publish(spec)
        echo("Published ${result.packageReference}")
        echo("Digest: ${result.digest}")
    }

    private fun mavenCommand(projectDir: Path): List<String> {
        val isWindows = System.getProperty("os.name").contains("windows", ignoreCase = true)
        val wrapper = projectDir.resolve(if (isWindows) "mvnw.cmd" else "mvnw")
        return if (Files.isRegularFile(wrapper)) {
            listOf(wrapper.toAbsolutePath().toString())
        } else {
            listOf("mvn")
        }
    }

    private fun runtimeDependencies(directory: Path, snapshot: Boolean): List<Path> {
        if (!Files.isDirectory(directory)) {
            return emptyList()
        }
        return Files.list(directory).use { paths ->
            paths.asSequence()
                .filter { Files.isRegularFile(it) }
                .filter { it.fileName.toString().endsWith(".jar") }
                .filter { it.fileName.toString().contains("-SNAPSHOT") == snapshot }
                .sortedBy { it.fileName.toString() }
                .toList()
        }
    }

    private fun mainClassFromBuiltArtifact(targetDirectory: Path, project: MavenProjectInfo): String? {
        if (!Files.isDirectory(targetDirectory)) {
            return null
        }
        val preferredName = "${project.artifactId}-${project.version}.jar"
        val jars = Files.list(targetDirectory).use { paths ->
            paths.asSequence()
                .filter { Files.isRegularFile(it) }
                .filter { it.fileName.toString().endsWith(".jar") }
                .filterNot { it.fileName.toString().endsWith(".jar.original") }
                .filterNot { it.fileName.toString().contains("-sources") }
                .filterNot { it.fileName.toString().contains("-javadoc") }
                .sortedWith(
                    compareByDescending<Path> { it.fileName.toString() == preferredName }
                        .thenBy { it.fileName.toString() }
                )
                .toList()
        }
        return jars.firstNotNullOfOrNull { jar ->
            JarFile(jar.toFile()).use { PackageNameSupport.mainClassFromManifest(it.manifest?.mainAttributes) }
        }
    }
}

interface ProcessRunner {
    fun run(command: List<String>, workingDirectory: Path)
}

class DefaultProcessRunner : ProcessRunner {
    override fun run(command: List<String>, workingDirectory: Path) {
        val process = ProcessBuilder(command)
            .directory(workingDirectory.toFile())
            .inheritIO()
            .start()
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            throw IllegalStateException("Maven build failed with exit code $exitCode.")
        }
    }
}

data class MavenProjectInfo(
    val groupId: String,
    val artifactId: String,
    val version: String
)

private object MavenPomReader {
    fun read(pom: Path): MavenProjectInfo {
        val document = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(pom.toFile())
        val project = document.documentElement
        val parent = project.firstDirectChild("parent")
        val artifactId = project.directChildText("artifactId")
            ?: throw IllegalStateException("pom.xml is missing artifactId.")
        val groupId = project.directChildText("groupId")
            ?: parent?.directChildText("groupId")
            ?: throw IllegalStateException("pom.xml is missing groupId.")
        val version = project.directChildText("version")
            ?: parent?.directChildText("version")
            ?: "dev"
        return MavenProjectInfo(groupId, artifactId, version)
    }

    private fun Element.directChildText(tagName: String): String? =
        firstDirectChild(tagName)?.textContent?.trim()?.takeIf { it.isNotBlank() }

    private fun Element.firstDirectChild(tagName: String): Element? =
        (0 until childNodes.length)
            .asSequence()
            .map { childNodes.item(it) }
            .filterIsInstance<Element>()
            .firstOrNull { it.tagName == tagName }
}
