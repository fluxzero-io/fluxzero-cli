package host.flux.dev

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

fun interface DevLauncher {
    fun launch(request: DevLaunchRequest): Int
}

class DevServerLauncher(
    executor: CommandExecutor? = null,
    private val environment: Map<String, String> = System.getenv(),
    private val messageSink: (String) -> Unit = { System.err.println(it) }
) : DevLauncher {
    private val executor = executor ?: InheritedIoCommandExecutor()

    override fun launch(request: DevLaunchRequest): Int {
        val projectDirectory = request.projectDirectory.toAbsolutePath().normalize()
        val version = request.devServerVersion?.takeIf { it.isNotBlank() }
            ?: environment["FLUXZERO_DEV_SERVER_VERSION"]?.takeIf { it.isNotBlank() }
            ?: FluxzeroProjectVersion.detect(projectDirectory)
            ?: error(
                "Could not detect the Fluxzero SDK version from the build in $projectDirectory. " +
                    "Set --dev-server-version or FLUXZERO_DEV_SERVER_VERSION."
            )
        require(!request.detached || request.target == DevLaunchTarget.SERVER) {
            "Only the Fluxzero dev server can be started in the background"
        }
        val shutdown = ShutdownOutcome(request.target == DevLaunchTarget.SERVER && !request.detached, messageSink)
        return executor.supervise(shutdown::report) {
            try {
                val classpath = DevServerClasspathResolver(executor, messageSink)
                    .resolve(projectDirectory, version, reuseSnapshotCache = request.target != DevLaunchTarget.SERVER)
                val launcherProperty = if (request.target == DevLaunchTarget.SERVER && !request.detached) {
                    listOf("-Dfluxzero.dev.launcherOwnsShutdown=true")
                } else {
                    emptyList()
                }
                val command = listOf(
                    javaExecutable(),
                    "--enable-native-access=ALL-UNNAMED"
                ) + launcherProperty + request.jvmOptions + listOf(
                    "-cp",
                    classpath,
                    request.target.mainClass
                ) + if (request.detached) detachedArguments(request.arguments) else request.arguments
                if (request.detached) {
                    launchDetached(command, projectDirectory)
                } else executor.execute(command, projectDirectory, OutputMode.INHERIT).also { exitCode ->
                    if (request.target == DevLaunchTarget.SERVER && (exitCode == 0 || exitCode == 130 || exitCode == 143)) {
                        shutdown.report()
                    }
                    if (request.target == DevLaunchTarget.CONTROL && request.arguments.firstOrNull() == "stop") {
                        executor.releaseDetached(projectDirectory)
                    }
                }
            } catch (e: DevLaunchInterruptedException) {
                shutdown.report()
                e.exitCode
            }
        }
    }

    private fun launchDetached(command: List<String>, projectDirectory: Path): Int {
        val bootstrapLog = projectDirectory.resolve(".fluxzero/dev/bootstrap.log")
        val pid = executor.startDetached(command, projectDirectory, bootstrapLog)
        val waitCommand = command.takeWhile { it != "-cp" } + listOf(
            "-cp",
            command[command.indexOf("-cp") + 1],
            DevLaunchTarget.CONTROL.mainClass,
            "wait",
            "--project-dir", projectDirectory.toString(),
            "--pid", pid.toString()
        ) + if (command.contains("--no-compile-on-start")) listOf("--allow-empty") else emptyList()
        return try {
            executor.execute(waitCommand, projectDirectory, OutputMode.INHERIT).also { exitCode ->
                if (exitCode == 130 || exitCode == 143) {
                    stopDetached(projectDirectory, command)
                } else if (exitCode == 2) {
                    executor.releaseDetached(projectDirectory)
                }
            }
        } catch (e: DevLaunchInterruptedException) {
            stopDetached(projectDirectory, command)
            throw e
        }
    }

    private fun detachedArguments(arguments: List<String>): List<String> = buildList {
        addAll(arguments)
        addEnvironmentOption(arguments, "--main-class", "FLUXZERO_MAIN_CLASS")
        addEnvironmentOption(arguments, "--application-name", "FLUXZERO_APPLICATION_NAME")
        addEnvironmentOption(arguments, "--namespace", "FLUXZERO_NAMESPACE")
        addEnvironmentOption(arguments, "--port", "FLUXZERO_DEV_PORT")
        if ("--app" !in arguments) {
            environment["FLUXZERO_DEV_APPS"]?.split(',')?.map(String::trim)?.filter(String::isNotEmpty)
                ?.forEach { addAll(listOf("--app", it)) }
        }
    }

    private fun MutableList<String>.addEnvironmentOption(
        originalArguments: List<String>, option: String, variable: String
    ) {
        if (option !in originalArguments) {
            environment[variable]?.takeIf(String::isNotBlank)?.let { addAll(listOf(option, it)) }
        }
    }

    private fun stopDetached(projectDirectory: Path, serverCommand: List<String>) {
        val classpathIndex = serverCommand.indexOf("-cp")
        if (classpathIndex < 0) return
        val stopCommand = serverCommand.take(classpathIndex) + listOf(
            "-cp", serverCommand[classpathIndex + 1],
            DevLaunchTarget.CONTROL.mainClass,
            "stop", "--project-dir", projectDirectory.toString(), "--force"
        )
        runCatching { executor.execute(stopCommand, projectDirectory, OutputMode.INHERIT) }
        executor.releaseDetached(projectDirectory)
    }

    private fun javaExecutable(): String {
        val javaHome = environment["JAVA_HOME"]?.takeIf { it.isNotBlank() }
        if (javaHome != null) {
            val executable = Path.of(javaHome, "bin", if (isWindows()) "java.exe" else "java")
            if (Files.isRegularFile(executable)) return executable.toString()
        }
        val currentRuntime = Path.of(System.getProperty("java.home"), "bin", if (isWindows()) "java.exe" else "java")
        return if (Files.isRegularFile(currentRuntime)) currentRuntime.toString()
        else if (isWindows()) "java.exe" else "java"
    }

    private fun isWindows() = System.getProperty("os.name").lowercase().contains("win")

    private class ShutdownOutcome(
        private val enabled: Boolean,
        private val messageSink: (String) -> Unit
    ) {
        private val reported = AtomicBoolean()

        fun report() {
            if (enabled && reported.compareAndSet(false, true)) {
                messageSink("\nFluxzero dev stopped.")
            }
        }
    }
}
