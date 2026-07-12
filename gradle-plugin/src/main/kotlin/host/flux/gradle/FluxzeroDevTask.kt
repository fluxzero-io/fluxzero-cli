package host.flux.gradle

import host.flux.dev.DevLaunchRequest
import host.flux.dev.DevLaunchTarget
import host.flux.dev.DevServerLauncher
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.options.Option
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "Runs a long-lived local development environment")
abstract class FluxzeroDevTask : DefaultTask() {
    @get:Internal abstract val projectDirectory: DirectoryProperty
    @get:Input @get:Optional abstract val serverVersion: Property<String>
    @get:Input @get:Optional abstract val mainClass: Property<String>
    @get:Input @get:Optional abstract val applicationName: Property<String>
    @get:Input abstract val applications: ListProperty<String>
    @get:Input abstract val environment: Property<String>
    @get:Input @get:Optional abstract val port: Property<Int>
    @get:Input @get:Optional abstract val idp: Property<String>
    @get:Input @get:Optional abstract val namespace: Property<String>
    @get:Input abstract val watch: Property<Boolean>
    @get:Input abstract val compileOnStart: Property<Boolean>
    @get:Input abstract val testsEnabled: Property<Boolean>
    @get:Input abstract val fastCompiler: Property<Boolean>
    @get:Input @get:Optional abstract val frontendCommand: Property<String>
    @get:Input @get:Optional abstract val frontendUrl: Property<String>
    @get:Input abstract val frontendEnabled: Property<Boolean>
    @get:Input abstract val backendPaths: ListProperty<String>
    @get:Input abstract val appArgs: ListProperty<String>
    @get:Input @get:Optional abstract val startupTimeoutMillis: Property<Long>
    @get:Input @get:Optional abstract val gracefulShutdownTimeoutMillis: Property<Long>
    @get:Input @get:Optional abstract val debounceMillis: Property<Long>
    @get:Input abstract val background: Property<Boolean>

    @Option(option = "dev-server-version", description = "Fluxzero dev-server artifact version.")
    fun devServerVersionOption(value: String) = serverVersion.set(value)

    @Option(option = "main-class", description = "Application main class override; auto-detected by default.")
    fun mainClassOption(value: String) = mainClass.set(value)

    @Option(option = "application-name", description = "Fluxzero application name.")
    fun applicationNameOption(value: String) = applicationName.set(value)

    @Option(option = "applications", description = "Comma-separated application, module, test-app, or flavor selectors.")
    fun applicationsOption(value: String) = applications.set(commaSeparated(value))

    @Option(option = "environment", description = "Application environment/profile; defaults to local.")
    fun environmentOption(value: String) = environment.set(value)

    @Option(option = "port", description = "Public gateway port; dynamically allocated by default.")
    fun portOption(value: String) = port.set(integer(value, "port"))

    @Option(option = "idp", description = "IDP mode: managed or external.")
    fun idpOption(value: String) = idp.set(value)

    @Option(option = "namespace", description = "Fluxzero namespace/project id.")
    fun namespaceOption(value: String) = namespace.set(value)

    @Option(option = "watch", description = "Watch application sources and rebuild on changes.")
    fun watchOption(value: Boolean) = watch.set(value)

    @Option(option = "compile-on-start", description = "Compile and launch applications during startup.")
    fun compileOnStartOption(value: Boolean) = compileOnStart.set(value)

    @Option(option = "tests", description = "Run selected tests in the background.")
    fun testsOption(value: Boolean) = testsEnabled.set(value)

    @Option(option = "fast-compiler", description = "Enable the Maven-only fast Java compiler with Maven fallback.")
    fun fastCompilerOption(value: Boolean) = fastCompiler.set(value)

    @Option(option = "frontend-command", description = "Managed frontend dev-server command.")
    fun frontendCommandOption(value: String) = frontendCommand.set(value)

    @Option(option = "frontend-url", description = "Externally managed frontend URL.")
    fun frontendUrlOption(value: String) = frontendUrl.set(value)

    @Option(option = "frontend", description = "Enable frontend configuration from project settings.")
    fun frontendOption(value: Boolean) = frontendEnabled.set(value)

    @Option(option = "backend-paths", description = "Comma-separated frontend paths routed unchanged to Fluxzero.")
    fun backendPathsOption(value: String) = backendPaths.set(commaSeparated(value))

    @Option(option = "app-args", description = "Comma-separated application arguments.")
    fun appArgsOption(value: String) = appArgs.set(commaSeparated(value))

    @Option(option = "startup-timeout-ms", description = "Application readiness timeout in milliseconds.")
    fun startupTimeoutOption(value: String) = startupTimeoutMillis.set(long(value, "startup-timeout-ms"))

    @Option(option = "graceful-shutdown-timeout-ms", description = "Graceful app shutdown timeout in milliseconds.")
    fun shutdownTimeoutOption(value: String) =
        gracefulShutdownTimeoutMillis.set(long(value, "graceful-shutdown-timeout-ms"))

    @Option(option = "debounce-ms", description = "Source watcher debounce in milliseconds.")
    fun debounceOption(value: String) = debounceMillis.set(long(value, "debounce-ms"))

    @Option(option = "background", description = "Start detached; foreground remains the default.")
    fun backgroundOption(value: Boolean) = background.set(value)

    @TaskAction
    fun launch() {
        val root = projectDirectory.asFile.get().toPath().toAbsolutePath().normalize()
        val arguments = buildList {
            option("--project-dir", root.toString())
            option("--main-class", mainClass.orNull)
            option("--application-name", applicationName.orNull)
            applications.get().forEach { option("--app", it) }
            option("--environment", environment.orNull)
            option("--port", port.orNull?.toString())
            option("--idp", idp.orNull)
            option("--namespace", namespace.orNull)
            flag("--no-watch", !watch.get())
            flag("--no-compile-on-start", !compileOnStart.get())
            flag("--no-tests", !testsEnabled.get())
            flag("--fast-compiler", fastCompiler.get())
            option("--frontend-command", frontendCommand.orNull)
            option("--frontend-url", frontendUrl.orNull)
            flag("--no-frontend", !frontendEnabled.get())
            option("--startup-timeout-ms", startupTimeoutMillis.orNull?.toString())
            option("--graceful-shutdown-timeout-ms", gracefulShutdownTimeoutMillis.orNull?.toString())
            option("--debounce-ms", debounceMillis.orNull?.toString())
            backendPaths.get().forEach { option("--backend-path", it) }
            appArgs.get().forEach { option("--app-arg", it) }
        }
        val exitCode = DevServerLauncher(messageSink = logger::lifecycle).launch(
            DevLaunchRequest(
                root, serverVersion.orNull, DevLaunchTarget.SERVER, arguments,
                detached = background.get(),
                jvmOptions = listOf("-Dfluxzero.dev.gradle.noDaemon=true")
            )
        )
        if (exitCode != 0 && exitCode != 130 && exitCode != 143) {
            throw GradleException("Fluxzero dev server exited with code $exitCode")
        }
    }

    private fun MutableList<String>.flag(name: String, enabled: Boolean) {
        if (enabled) add(name)
    }

    private fun MutableList<String>.option(name: String, value: String?) {
        if (value != null) {
            add(name)
            add(value)
        }
    }

    private fun commaSeparated(value: String): List<String> =
        value.split(',').map(String::trim).filter(String::isNotEmpty)

    private fun integer(value: String, option: String): Int = value.toIntOrNull()
        ?: throw GradleException("Invalid --$option value '$value': expected an integer")

    private fun long(value: String, option: String): Long = value.toLongOrNull()
        ?: throw GradleException("Invalid --$option value '$value': expected an integer")
}
