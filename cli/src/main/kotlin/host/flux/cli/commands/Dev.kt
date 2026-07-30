package host.flux.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.types.long
import com.github.ajalt.clikt.parameters.types.path
import host.flux.dev.DevLaunchRequest
import host.flux.dev.DevLaunchTarget
import host.flux.dev.DevLauncher
import host.flux.dev.DevServerLauncher
import java.nio.file.Files
import java.nio.file.Path

class Dev(
    private val launcher: DevLauncher = DevServerLauncher(),
    private val projectInitializer: DevProjectInitializer = InteractiveDevProjectInitializer()
) : CliktCommand() {
    override fun help(context: Context): String = "Start the local Fluxzero development environment"
    override fun helpEpilog(context: Context): String =
        "Shared project defaults belong in .fluxzero/dev.yaml. " +
            "Run `fz dev config` to print the version-aligned configuration reference."

    private val action by argument(
        help = "Action: start (default), config, list, attach, status, logs, or stop."
    ).optional()

    private val projectDirectory by option("--project-dir", "--dir", help = "Maven or Gradle project directory.")
        .path(mustExist = true, canBeFile = false, canBeDir = true)
        .default(Path.of(""))
    private val devServerVersion by option(
        "--dev-server-version",
        help = "Dev-server artifact version override. Defaults to the active project pin or latest stable 1.x release."
    )
    private val mainClass by option("--main-class", help = "Application main class override; auto-detected by default.")
    private val applicationName by option("--application-name", help = "Fluxzero application name.")
    private val applications by option(
        "--app",
        help = "Application/module or named project configuration to start; repeatable. " +
            "Starts all discovered applications by default."
    ).multiple()
    private val environment by option(
        "--environment",
        help = "Application environment/profile. Defaults to local."
    )
    private val port by option(
        "--port", "--gateway-port",
        help = "Public gateway port. Defaults to a dynamically allocated free port."
    ).long()
    private val idp by option(
        "--idp",
        help = "IDP mode: managed or external. Defaults to managed."
    )
    private val noIdp by option(
        "--no-idp",
        help = "Use application-owned external IDP configuration; alias for --idp external."
    ).flag(default = false)
    private val namespace by option("--namespace", help = "Fluxzero namespace/project id.")
    private val noWatch by option("--no-watch", help = "Disable source watching.").flag(default = false)
    private val noCompileOnStart by option("--no-compile-on-start", help = "Do not compile and launch on startup.")
        .flag(default = false)
    private val noTests by option("--no-tests", help = "Disable background tests.").flag(default = false)
    private val fastCompiler by option("--fast-compiler", help = "Enable the Maven-correct fast Java compiler.")
        .flag(default = false)
    private val startupTimeout by option("--startup-timeout-ms", help = "Application readiness timeout in milliseconds.")
        .long()
    private val shutdownTimeout by option(
        "--graceful-shutdown-timeout-ms",
        help = "Graceful app shutdown timeout in milliseconds."
    ).long()
    private val debounce by option("--debounce-ms", help = "Source watcher debounce in milliseconds.").long()
    private val frontendCommand by option("--frontend-command", help = "Frontend dev-server command.")
    private val frontendDirectory by option(
        "--frontend-directory",
        help = "Working directory for managed frontend commands."
    )
    private val frontendSetupCommand by option(
        "--frontend-setup-command",
        help = "Optional setup command run once before the managed frontend starts for this dev session."
    )
    private val frontendUrl by option("--frontend-url", help = "Externally managed frontend URL.")
    private val noFrontend by option(
        "--no-frontend",
        help = "Run backend-only, ignoring frontend settings from project configuration."
    ).flag(default = false)
    private val backendPaths by option(
        "--backend-path",
        help = "Additional frontend path routed unchanged to Fluxzero; repeatable."
    ).multiple()
    private val appArgs by option("--app-arg", help = "Application argument; repeatable.").multiple()
    private val background by option(
        "-d", "--background", "--detach",
        help = "Start without attaching a live view; return after startup completes."
    ).flag(default = false)
    private val follow by option("-f", "--follow", help = "Follow output for the logs action.").flag(default = false)
    private val errors by option("--errors", help = "Show only warning and error log lines.").flag(default = false)
    private val json by option("--json", help = "Print machine-readable status or environment-list JSON.")
        .flag(default = false)
    private val force by option("--force", help = "Force termination for the stop action.").flag(default = false)
    private val all by option("--all", help = "Apply the stop action to all registered dev environments.")
        .flag(default = false)
    private val idleTimeout by option(
        "--idle-timeout",
        help = "Stop a ready environment after inactivity, for example 8h or disabled."
    )
    private val failedStartupTimeout by option(
        "--failed-startup-timeout",
        help = "Stop an environment that remains unready and inactive, for example 10m or disabled."
    )

    override fun run() {
        var root = projectDirectory.toAbsolutePath().normalize()
        val selectedAction = action ?: "start"
        if (selectedAction !in setOf("start", "config", "list", "attach", "status", "logs", "stop")) {
            throw UsageError(
                "Unknown dev action '$selectedAction'. Expected start, config, list, attach, status, logs, or stop."
            )
        }
        if (all && selectedAction != "stop") {
            throw UsageError("--all is only supported by fz dev stop")
        }
        if (selectedAction == "start" && !isBuildProject(root)) {
            root = try {
                projectInitializer.initialize(root)
            } catch (e: Exception) {
                throw UsageError(e.message ?: "Could not initialize a Fluxzero project")
            } ?: throw UsageError(
                "No project was created. Run fz init or fz dev from a Maven or Gradle project root."
            )
        }
        if (selectedAction == "config") {
            val exitCode = launcher.launch(
                DevLaunchRequest(root, devServerVersion, DevLaunchTarget.CONFIG)
            )
            if (exitCode != 0) throw ProgramResult(exitCode)
            return
        }
        if (selectedAction != "start") {
            val controlArguments = buildList {
                add(selectedAction)
                addOption("--project-dir", root.toString())
                addFlag("--json", json)
                addFlag("--follow", follow)
                addFlag("--errors", errors)
                addFlag("--force", force)
                addFlag("--all", all)
                applications.singleOrNull()?.let { addOption("--app", it) }
            }
            val exitCode = launcher.launch(
                DevLaunchRequest(root, devServerVersion, DevLaunchTarget.CONTROL, controlArguments)
            )
            if (exitCode != 0) throw ProgramResult(exitCode)
            return
        }
        val arguments = buildList {
            addOption("--project-dir", root.toString())
            addOption("--main-class", mainClass)
            addOption("--application-name", applicationName)
            applications.forEach { addOption("--app", it) }
            addOption("--environment", environment)
            addOption("--port", port?.toString())
            addOption("--idp", if (noIdp) "external" else idp)
            addOption("--namespace", namespace)
            addFlag("--no-watch", noWatch)
            addFlag("--no-compile-on-start", noCompileOnStart)
            addFlag("--no-tests", noTests)
            addFlag("--fast-compiler", fastCompiler)
            addOption("--startup-timeout-ms", startupTimeout?.toString())
            addOption("--graceful-shutdown-timeout-ms", shutdownTimeout?.toString())
            addOption("--debounce-ms", debounce?.toString())
            addOption("--idle-timeout", idleTimeout)
            addOption("--failed-startup-timeout", failedStartupTimeout)
            addOption("--frontend-command", frontendCommand)
            addOption("--frontend-directory", frontendDirectory)
            addOption("--frontend-setup-command", frontendSetupCommand)
            addOption("--frontend-url", frontendUrl)
            addFlag("--no-frontend", noFrontend)
            backendPaths.forEach { addOption("--backend-path", it) }
            appArgs.forEach { addOption("--app-arg", it) }
        }
        val exitCode = launcher.launch(
            DevLaunchRequest(root, devServerVersion, DevLaunchTarget.SERVER, arguments, background)
        )
        if (exitCode != 0 && exitCode != 130 && exitCode != 143) {
            throw ProgramResult(exitCode)
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

    private fun isBuildProject(directory: Path): Boolean = listOf(
        "pom.xml",
        "build.gradle",
        "build.gradle.kts",
        "settings.gradle",
        "settings.gradle.kts"
    ).any { Files.isRegularFile(directory.resolve(it)) }
}
