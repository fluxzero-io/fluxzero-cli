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
                "Could not detect the Fluxzero SDK version from $projectDirectory/pom.xml. " +
                    "Set --dev-server-version or FLUXZERO_DEV_SERVER_VERSION."
            )
        val shutdown = ShutdownOutcome(request.target == DevLaunchTarget.SERVER, messageSink)
        return executor.supervise(shutdown::report) {
            try {
                val classpath = DevServerClasspathResolver(executor, messageSink)
                    .resolve(projectDirectory, version)
                val launcherProperty = if (request.target == DevLaunchTarget.SERVER) {
                    listOf("-Dfluxzero.dev.launcherOwnsShutdown=true")
                } else {
                    emptyList()
                }
                val command = listOf(
                    javaExecutable(),
                    "--enable-native-access=ALL-UNNAMED"
                ) + launcherProperty + listOf(
                    "-cp",
                    classpath,
                    request.target.mainClass
                ) + request.arguments
                executor.execute(command, projectDirectory, OutputMode.INHERIT).also { exitCode ->
                    if (request.target == DevLaunchTarget.SERVER && (exitCode == 0 || exitCode == 130 || exitCode == 143)) {
                        shutdown.report()
                    }
                }
            } catch (e: DevLaunchInterruptedException) {
                shutdown.report()
                e.exitCode
            }
        }
    }

    private fun javaExecutable(): String {
        val javaHome = environment["JAVA_HOME"]?.takeIf { it.isNotBlank() }
        if (javaHome != null) {
            val executable = Path.of(javaHome, "bin", if (isWindows()) "java.exe" else "java")
            if (Files.isRegularFile(executable)) return executable.toString()
        }
        return if (isWindows()) "java.exe" else "java"
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
