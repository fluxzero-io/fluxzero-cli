package host.flux.cli.services

import host.flux.cli.prompt.JLinePrompt
import host.flux.cli.prompt.Prompt
import host.flux.dev.JavaRequiredException
import host.flux.dev.JavaRuntime
import host.flux.dev.JavaRuntimeDiscovery
import host.flux.dev.JavaRuntimeProvider
import host.flux.dev.REQUIRED_JAVA_FEATURE
import java.util.Locale
import java.util.concurrent.TimeUnit

internal fun interface JavaInstaller {
    fun install(): Boolean
}

internal class SystemJavaInstaller(
    private val osName: String = System.getProperty("os.name"),
    private val commandAvailable: (List<String>) -> Boolean = ::commandAvailable,
    private val execute: (List<String>) -> Boolean = ::execute
) {
    fun available(): JavaInstaller? {
        val command = when {
            osName.lowercase(Locale.ROOT).contains("windows") &&
                commandAvailable(listOf("winget", "--version")) -> listOf(
                    "winget", "install", "--exact", "--id",
                    "EclipseAdoptium.Temurin.$REQUIRED_JAVA_FEATURE.JDK",
                    "--accept-package-agreements", "--accept-source-agreements", "--disable-interactivity"
                )
            commandAvailable(listOf("brew", "--version")) -> listOf(
                "brew", "install", "openjdk@$REQUIRED_JAVA_FEATURE"
            )
            else -> return null
        }
        return JavaInstaller { execute(command) }
    }

    companion object {
        private fun commandAvailable(command: List<String>): Boolean = runCatching {
            val process = ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start()
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                process.waitFor(500, TimeUnit.MILLISECONDS)
                false
            } else {
                process.exitValue() == 0
            }
        }.getOrDefault(false)

        private fun execute(command: List<String>): Boolean = runCatching {
            ProcessBuilder(command).inheritIO().start().waitFor() == 0
        }.getOrDefault(false)
    }
}

internal class JavaSetup(
    private val discovery: JavaRuntimeDiscovery = JavaRuntimeDiscovery(),
    private val installer: () -> JavaInstaller? = { SystemJavaInstaller().available() },
    private val prompt: () -> Prompt = { JLinePrompt() },
    private val messageSink: (String) -> Unit = { System.err.println(it) },
    private val interactive: Boolean = true
) : JavaRuntimeProvider {
    override fun resolve(): JavaRuntime {
        discovery.find()?.let { return it }
        if (!interactive) throw JavaRequiredException()
        val availableInstaller = installer() ?: throw JavaRequiredException()
        val actualPrompt = prompt()
        if (!actualPrompt.isInteractive()) throw JavaRequiredException()

        messageSink("Java $REQUIRED_JAVA_FEATURE is needed to start Fluxzero.")
        messageSink("")
        val selection = actualPrompt.select(
            "Would you like to install it now?",
            listOf("Install Java $REQUIRED_JAVA_FEATURE", "Cancel")
        )
        if (selection != 0) throw JavaRequiredException()

        messageSink("")
        messageSink("Installing Java $REQUIRED_JAVA_FEATURE...")
        if (!availableInstaller.install()) {
            throw IllegalStateException(
                "Java $REQUIRED_JAVA_FEATURE could not be installed. Install it and try again."
            )
        }
        return discovery.find() ?: throw IllegalStateException(
            "Java $REQUIRED_JAVA_FEATURE was installed but is not available yet. Open a new terminal and try again."
        )
    }
    companion object {
        fun nonInteractive(): JavaRuntimeProvider = JavaSetup(interactive = false)
    }
}
