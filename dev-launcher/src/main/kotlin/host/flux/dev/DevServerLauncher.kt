package host.flux.dev

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

private const val PREFLIGHT_MAIN_CLASS = "io.fluxzero.devserver.DevServerPreflightMain"
private const val USE_DYNAMIC_PORT_EXIT_CODE = 75
private const val CANCEL_STARTUP_EXIT_CODE = 76

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
        val shutdown = ShutdownOutcome(messageSink).also {
            if (request.target == DevLaunchTarget.SERVER) it.expectStopped()
        }
        return executor.supervise(shutdown::begin, shutdown::complete) {
            try {
                val resolver = DevServerClasspathResolver(executor, messageSink)
                val likelyActive = request.target == DevLaunchTarget.SERVER && likelyActive(projectDirectory)
                var classpath = resolver.resolve(
                    projectDirectory, version,
                    reuseSnapshotCache = request.target != DevLaunchTarget.SERVER || likelyActive
                )
                var command = command(classpath, request)
                if (request.target == DevLaunchTarget.SERVER) {
                    var active = likelyActive && probe(command, projectDirectory)
                    if (likelyActive && !active) {
                        classpath = resolver.resolve(projectDirectory, version, reuseSnapshotCache = false)
                        command = command(classpath, request)
                        active = false
                    }
                    launchServer(command, projectDirectory, request.detached, shutdown, active)
                } else {
                    if (request.arguments.firstOrNull() == "attach") {
                        shutdown.expectStopped {
                            stopDetached(projectDirectory, command, OutputMode.DISCARD, cleanup = true, force = false)
                        }
                    }
                    val exitCode = executor.execute(command, projectDirectory, OutputMode.INHERIT)
                    if (request.target == DevLaunchTarget.CONTROL && request.arguments.firstOrNull() == "stop") {
                        executor.releaseDetached(projectDirectory)
                    }
                    if (request.target == DevLaunchTarget.CONTROL && request.arguments.firstOrNull() == "attach"
                        && exitCode in setOf(130, 143)) {
                        shutdown.report()
                        0
                    } else {
                        exitCode
                    }
                }
            } catch (e: DevLaunchInterruptedException) {
                shutdown.report()
                e.exitCode
            }
        }
    }

    private fun command(classpath: String, request: DevLaunchRequest): List<String> = listOf(
        javaExecutable(),
        "--enable-native-access=ALL-UNNAMED"
    ) + unsafeMemoryOption() + request.jvmOptions + listOf(
        "-cp",
        classpath,
        request.target.mainClass
    ) + request.arguments

    private fun launchServer(
        command: List<String>, projectDirectory: Path, detached: Boolean, shutdown: ShutdownOutcome, active: Boolean
    ): Int {
        if (active) {
            if (detached) {
                messageSink("Fluxzero dev is already running in the background.")
                return 0
            }
            shutdown.expectStopped {
                stopDetached(projectDirectory, command, OutputMode.DISCARD, cleanup = true, force = false)
            }
            return attach(command, projectDirectory, -1, shutdown)
        }
        val detachedCommand = command.takeWhile { it != DevLaunchTarget.SERVER.mainClass } +
            DevLaunchTarget.SERVER.mainClass + detachedArguments(command.dropWhile {
                it != DevLaunchTarget.SERVER.mainClass
            }.drop(1))
        val preflightExitCode = executor.execute(
            preflightCommand(detachedCommand), projectDirectory, OutputMode.INHERIT
        )
        val launchCommand = when (preflightExitCode) {
            0 -> detachedCommand
            USE_DYNAMIC_PORT_EXIT_CODE -> detachedCommand + listOf("--port", "0")
            CANCEL_STARTUP_EXIT_CODE -> return 0
            else -> return preflightExitCode
        }
        return if (detached) launchDetached(launchCommand, projectDirectory, shutdown)
        else launchAttached(launchCommand, projectDirectory, shutdown)
    }

    private fun probe(command: List<String>, projectDirectory: Path): Boolean = executor.execute(
        controlCommand(command, "probe", "--project-dir", projectDirectory.toString()),
        projectDirectory,
        OutputMode.DISCARD
    ) == 0

    private fun likelyActive(projectDirectory: Path): Boolean {
        val sessionFile = projectDirectory.resolve(".fluxzero/dev/session.json")
        if (!Files.isRegularFile(sessionFile)) return false
        return runCatching {
            val content = Files.readString(sessionFile)
            val status = Regex("\"status\"\\s*:\\s*\"([^\"]+)\"").find(content)?.groupValues?.get(1)
            val pid = Regex("\"pid\"\\s*:\\s*(\\d+)").find(content)?.groupValues?.get(1)?.toLongOrNull()
            status !in setOf(null, "stopped", "stopped-unexpectedly") && pid != null &&
                ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)
        }.getOrDefault(false)
    }

    private fun launchAttached(command: List<String>, projectDirectory: Path, shutdown: ShutdownOutcome): Int {
        val bootstrapLog = projectDirectory.resolve(".fluxzero/dev/bootstrap.log")
        val startedPid = CompletableFuture<Long>()
        shutdown.expectStopped {
            val pid = runCatching { startedPid.get(5, TimeUnit.SECONDS) }.getOrNull()
            stopDetached(projectDirectory, command, OutputMode.DISCARD, cleanup = true, force = false)
            if (pid != null && ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
                stopDetachedProcess(projectDirectory, pid)
            }
        }
        val pid = try {
            executor.startDetached(command, projectDirectory, bootstrapLog).also(startedPid::complete)
        } catch (e: Exception) {
            startedPid.completeExceptionally(e)
            throw e
        }
        return attach(command, projectDirectory, pid, shutdown)
    }

    private fun attach(command: List<String>, projectDirectory: Path, pid: Long, shutdown: ShutdownOutcome): Int {
        val arguments = buildList {
            add("attach")
            addAll(listOf("--project-dir", projectDirectory.toString()))
            if (pid > 0) addAll(listOf("--pid", pid.toString()))
        }
        val exitCode = executor.execute(controlCommand(command, *arguments.toTypedArray()), projectDirectory,
                                        OutputMode.INHERIT)
        if (exitCode in setOf(130, 143)) {
            shutdown.report()
            return 0
        }
        val sessionFile = projectDirectory.resolve(".fluxzero/dev/session.json")
        val stillRunning = Files.isRegularFile(sessionFile) && executor.execute(
                controlCommand(command, "probe", "--project-dir", projectDirectory.toString()),
                projectDirectory,
                OutputMode.DISCARD
            ) == 0
        if (!stillRunning) {
            executor.releaseDetached(projectDirectory)
        }
        return exitCode
    }

    private fun launchDetached(command: List<String>, projectDirectory: Path, shutdown: ShutdownOutcome): Int {
        val bootstrapLog = projectDirectory.resolve(".fluxzero/dev/bootstrap.log")
        val pid = executor.startDetached(command, projectDirectory, bootstrapLog)
        shutdown.expectStopped { stopDetachedProcess(projectDirectory, pid) }
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

    private fun controlCommand(serverCommand: List<String>, vararg arguments: String): List<String> {
        val mainIndex = serverCommand.indexOf(DevLaunchTarget.SERVER.mainClass)
        require(mainIndex >= 0) { "Fluxzero dev server main class is missing from launch command" }
        return serverCommand.take(mainIndex) + DevLaunchTarget.CONTROL.mainClass + arguments
    }

    private fun preflightCommand(serverCommand: List<String>): List<String> {
        val mainIndex = serverCommand.indexOf(DevLaunchTarget.SERVER.mainClass)
        require(mainIndex >= 0) { "Fluxzero dev server main class is missing from launch command" }
        return serverCommand.take(mainIndex) + PREFLIGHT_MAIN_CLASS + serverCommand.drop(mainIndex + 1)
    }

    private fun stopDetachedProcess(projectDirectory: Path, pid: Long) {
        ProcessHandle.of(pid).ifPresent { process ->
            process.destroy()
            val deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos()
            while (process.isAlive && System.nanoTime() < deadline) {
                TimeUnit.MILLISECONDS.sleep(25)
            }
            if (process.isAlive) process.destroyForcibly()
        }
        executor.releaseDetached(projectDirectory)
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

    private fun stopDetached(
        projectDirectory: Path,
        serverCommand: List<String>,
        outputMode: OutputMode = OutputMode.INHERIT,
        cleanup: Boolean = false,
        force: Boolean = true
    ) {
        val classpathIndex = serverCommand.indexOf("-cp")
        if (classpathIndex < 0) return
        val stopCommand = serverCommand.take(classpathIndex) + listOf(
            "-cp", serverCommand[classpathIndex + 1],
            DevLaunchTarget.CONTROL.mainClass,
            "stop", "--project-dir", projectDirectory.toString()
        ) + if (force) listOf("--force") else emptyList()
        runCatching {
            if (cleanup) executor.executeCleanup(stopCommand, projectDirectory, outputMode)
            else executor.execute(stopCommand, projectDirectory, outputMode)
        }
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

    private fun unsafeMemoryOption(): List<String> =
        if (Runtime.version().feature() >= 24) listOf("--sun-misc-unsafe-memory-access=allow") else emptyList()

    private fun isWindows() = System.getProperty("os.name").lowercase().contains("win")

    private inner class ShutdownOutcome(private val messageSink: (String) -> Unit) {
        private val expected = AtomicBoolean()
        private val stoppingReported = AtomicBoolean()
        private val stoppedReported = AtomicBoolean()
        private val action = AtomicReference<() -> Unit>({})

        fun expectStopped(beforeReport: () -> Unit = {}) {
            action.set(beforeReport)
            expected.set(true)
        }

        fun begin() {
            if (expected.get() && stoppingReported.compareAndSet(false, true)) {
                messageSink("\nStopping Fluxzero dev server and all started applications...")
                action.get().invoke()
            }
        }

        fun complete() {
            if (stoppingReported.get() && stoppedReported.compareAndSet(false, true)) {
                messageSink("Fluxzero dev server stopped.")
            }
        }

        fun report() {
            try {
                begin()
            } finally {
                complete()
            }
        }
    }
}
