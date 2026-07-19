package host.flux.maven

import host.flux.dev.DevLaunchRequest
import host.flux.dev.DevLaunchTarget
import host.flux.dev.DevServerLauncher
import org.apache.maven.plugin.AbstractMojo
import org.apache.maven.plugin.MojoExecutionException
import org.apache.maven.plugin.MojoFailureException
import org.apache.maven.plugins.annotations.Mojo
import org.apache.maven.plugins.annotations.Parameter
import java.io.File

@Mojo(name = "dev", aggregator = true, threadSafe = false)
class DevMojo : AbstractMojo() {
    @Parameter(defaultValue = "\${session.executionRootDirectory}", readonly = true)
    private lateinit var projectDir: File

    @Parameter(property = "fluxzero.dev.serverVersion")
    private var devServerVersion: String? = null

    @Parameter(property = "fluxzero.dev.mainClass")
    private var devMainClass: String? = null

    @Parameter(property = "fluxzero.dev.applicationName")
    private var devApplicationName: String? = null

    @Parameter(property = "fluxzero.dev.applications")
    private var applications: List<String> = emptyList()

    @Parameter(property = "fluxzero.dev.environment")
    private var environment: String? = null

    @Parameter(property = "fluxzero.dev.port")
    private var port: Int? = null

    @Parameter(property = "fluxzero.dev.idp")
    private var idp: String? = null

    @Parameter(property = "fluxzero.dev.namespace")
    private var devNamespace: String? = null

    @Parameter(property = "fluxzero.dev.watch", defaultValue = "true")
    private var watch: Boolean = true

    @Parameter(property = "fluxzero.dev.compileOnStart", defaultValue = "true")
    private var compileOnStart: Boolean = true

    @Parameter(property = "fluxzero.dev.testsEnabled", defaultValue = "true")
    private var testsEnabled: Boolean = true

    @Parameter(property = "fluxzero.dev.fastCompiler", defaultValue = "false")
    private var fastCompiler: Boolean = false

    @Parameter(property = "fluxzero.dev.frontendCommand")
    private var frontendCommand: String? = null

    @Parameter(property = "fluxzero.dev.frontendDirectory")
    private var frontendDirectory: String? = null

    @Parameter(property = "fluxzero.dev.frontendSetupCommand")
    private var frontendSetupCommand: String? = null

    @Parameter(property = "fluxzero.dev.frontendUrl")
    private var frontendUrl: String? = null

    @Parameter(property = "fluxzero.dev.frontendEnabled", defaultValue = "true")
    private var frontendEnabled: Boolean = true

    @Parameter
    private var backendPaths: List<String> = emptyList()

    @Parameter
    private var appArgs: List<String> = emptyList()

    @Parameter(property = "fluxzero.dev.startupTimeoutMillis")
    private var startupTimeoutMillis: Long? = null

    @Parameter(property = "fluxzero.dev.gracefulShutdownTimeoutMillis")
    private var gracefulShutdownTimeoutMillis: Long? = null

    @Parameter(property = "fluxzero.dev.debounceMillis")
    private var debounceMillis: Long? = null

    @Parameter(property = "fluxzero.dev.skip", defaultValue = "false")
    private var skipDev: Boolean = false

    @Parameter(property = "fluxzero.dev.background", defaultValue = "false")
    private var background: Boolean = false

    override fun execute() {
        if (skipDev) {
            log.info("Skipping Fluxzero dev environment")
            return
        }
        val root = projectDir.toPath().toAbsolutePath().normalize()
        val version = devServerVersion?.takeIf { it.isNotBlank() }
        val arguments = buildList {
            addOption("--project-dir", root.toString())
            addOption("--main-class", devMainClass)
            addOption("--application-name", devApplicationName)
            applications.forEach { addOption("--app", it) }
            addOption("--environment", environment)
            addOption("--port", port?.toString())
            addOption("--idp", idp)
            addOption("--namespace", devNamespace)
            addFlag("--no-watch", !watch)
            addFlag("--no-compile-on-start", !compileOnStart)
            addFlag("--no-tests", !testsEnabled)
            addFlag("--fast-compiler", fastCompiler)
            addOption("--frontend-command", frontendCommand)
            addOption("--frontend-directory", frontendDirectory)
            addOption("--frontend-setup-command", frontendSetupCommand)
            addOption("--frontend-url", frontendUrl)
            addFlag("--no-frontend", !frontendEnabled)
            addOption("--startup-timeout-ms", startupTimeoutMillis?.toString())
            addOption("--graceful-shutdown-timeout-ms", gracefulShutdownTimeoutMillis?.toString())
            addOption("--debounce-ms", debounceMillis?.toString())
            backendPaths.forEach { addOption("--backend-path", it) }
            appArgs.forEach { addOption("--app-arg", it) }
        }
        try {
            val exitCode = DevServerLauncher(messageSink = { log.info(it) }).launch(
                DevLaunchRequest(root, version, DevLaunchTarget.SERVER, arguments, detached = background)
            )
            if (exitCode != 0 && exitCode != 130 && exitCode != 143) {
                throw MojoFailureException("Fluxzero dev server exited with code $exitCode.")
            }
        } catch (e: MojoFailureException) {
            throw e
        } catch (e: Exception) {
            throw MojoExecutionException("Failed to start Fluxzero dev environment", e)
        }
    }

    private fun MutableList<String>.addFlag(name: String, enabled: Boolean) {
        if (enabled) add(name)
    }

    private fun MutableList<String>.addOption(name: String, value: String?) {
        if (value != null) {
            add(name)
            add(value)
        }
    }
}
