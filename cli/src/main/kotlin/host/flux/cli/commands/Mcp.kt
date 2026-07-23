package host.flux.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import host.flux.dev.CommandExecutor
import host.flux.dev.DevLaunchRequest
import host.flux.dev.DevLaunchTarget
import host.flux.dev.DevLauncher
import host.flux.dev.DevServerLauncher
import host.flux.dev.DevStartupReadiness
import host.flux.dev.InheritedIoCommandExecutor
import host.flux.dev.OutputMode
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

private const val DEV_SERVER_PREFLIGHT_MAIN = "io.fluxzero.devserver.DevServerPreflightMain"
private const val EMPTY_DEV_READINESS_ATTEMPTS = 10
private const val EMPTY_DEV_READINESS_RETRY_MILLIS = 100L
private const val EMPTY_DEV_READINESS_TIMEOUT_MILLIS = 10_000L
private val SESSION_STATUS = Regex("\"status\"\\s*:\\s*\"([^\"]+)\"")
private val MCP_STATE = Regex("\"mcp\"\\s*:\\s*\\{[^}]*\"state\"\\s*:\\s*\"([^\"]+)\"")

class Mcp(
    private val launcher: DevLauncher = DevServerLauncher(McpCommandExecutor()),
    private val readinessAttempts: Int = EMPTY_DEV_READINESS_ATTEMPTS,
    private val readinessPause: () -> Unit = { Thread.sleep(EMPTY_DEV_READINESS_RETRY_MILLIS) },
    private val readinessTimeoutMillis: Long = EMPTY_DEV_READINESS_TIMEOUT_MILLIS,
    private val monotonicNanos: () -> Long = System::nanoTime
) : CliktCommand() {
    init {
        require(readinessAttempts > 0) { "readinessAttempts must be greater than zero" }
        require(readinessTimeoutMillis >= 0) { "readinessTimeoutMillis must not be negative" }
    }

    override fun help(context: Context): String = "Connect stdio MCP to the active Fluxzero dev environment"

    private val projectDirectory by option("--project-dir", "--dir", help = "Fluxzero project directory.")
        .path(mustExist = true, canBeFile = false, canBeDir = true)
        .default(Path.of(""))
    private val devServerVersion by option(
        "--dev-server-version",
        help = "Dev-server artifact version override. Defaults to the active project pin or latest stable 1.x release."
    )
    private val ensureDev by option(
        "--ensure-dev",
        help = "Start one background dev environment when this project does not already have an active session."
    ).flag(default = false)
    private val allowEmpty by option(
        "--allow-empty",
        help = "Start the ensured dev environment without compiling a project on startup; requires --ensure-dev."
    ).flag(default = false)

    override fun run() {
        val root = projectDirectory.toAbsolutePath().normalize()
        if (allowEmpty && !ensureDev) {
            throw UsageError("--allow-empty requires --ensure-dev.")
        }
        if (ensureDev) {
            val startExitCode = launchInterruptibly(
                DevLaunchRequest(
                    root,
                    devServerVersion,
                    DevLaunchTarget.SERVER,
                    arguments = if (allowEmpty) listOf("--no-compile-on-start") else emptyList(),
                    detached = true,
                    startupReadiness = DevStartupReadiness.AGENT_CONTROL_PLANE
                ),
                "Interrupted while starting the Fluxzero dev environment."
            )
            check(startExitCode == 0) {
                "Fluxzero dev environment could not be started (exit code $startExitCode)."
            }
        }
        val mcpRequest = DevLaunchRequest(
            root,
            devServerVersion,
            DevLaunchTarget.MCP_STDIO,
            listOf("--project-dir", root.toString())
        )
        val exitCode = if (allowEmpty) launchEmptyMcpWhenReady(root, mcpRequest) else launchInterruptibly(
            mcpRequest,
            "Interrupted while running the Fluxzero MCP adapter."
        )
        check(exitCode == 0 || exitCode == 130 || exitCode == 143) {
            "Fluxzero MCP adapter exited with code $exitCode."
        }
    }

    private fun launchEmptyMcpWhenReady(root: Path, request: DevLaunchRequest): Int {
        val startedAt = monotonicNanos()
        val timeoutNanos = TimeUnit.MILLISECONDS.toNanos(readinessTimeoutMillis)
        var attemptsMade = 0
        var lastExitCode = 1
        var deadlineReached = false
        while (attemptsMade < readinessAttempts) {
            ensureNotInterrupted()
            val sessionWasMcpReady = sessionIsMcpReady(root)
            attemptsMade++
            lastExitCode = launchInterruptibly(
                request,
                "Interrupted while waiting for the empty Fluxzero dev environment to expose MCP."
            )
            if (lastExitCode == 0 || lastExitCode == 130 || lastExitCode == 143) return lastExitCode

            // The stdio entry point reports pre-transport readiness failures as exit 1. Once the session already
            // advertised MCP readiness, or for any other exit code, preserve the adapter failure instead of masking it.
            if (lastExitCode != 1 || sessionWasMcpReady) return lastExitCode

            deadlineReached = monotonicNanos() - startedAt >= timeoutNanos
            if (attemptsMade >= readinessAttempts || deadlineReached) break
            pauseForReadiness()
            deadlineReached = monotonicNanos() - startedAt >= timeoutNanos
            if (deadlineReached) break
        }
        val retryBound = if (deadlineReached) {
            "; ${readinessTimeoutMillis} ms retry window elapsed)."
        } else {
            ")."
        }
        throw UsageError(
            "The empty Fluxzero dev environment started, but its MCP adapter was not ready after " +
                "$attemptsMade launch attempts (last exit code $lastExitCode$retryBound " +
                "The adapter error output above contains the failure details. Inspect " +
                "${root.resolve(".fluxzero/dev/bootstrap.log")} and run fz dev status before trying again."
        )
    }

    private fun sessionIsMcpReady(root: Path): Boolean {
        val sessionFile = root.resolve(".fluxzero/dev/session.json")
        if (!Files.isRegularFile(sessionFile)) return false
        val content = try {
            Files.readString(sessionFile)
        } catch (e: InterruptedException) {
            interrupted("Interrupted while reading Fluxzero dev session readiness.")
        } catch (_: Exception) {
            return false
        }
        return SESSION_STATUS.find(content)?.groupValues?.get(1) == "running" &&
            MCP_STATE.find(content)?.groupValues?.get(1) == "running"
    }

    private fun pauseForReadiness() {
        try {
            readinessPause()
        } catch (_: InterruptedException) {
            interrupted("Interrupted while waiting for the empty Fluxzero dev environment to expose MCP.")
        }
    }

    private fun launchInterruptibly(request: DevLaunchRequest, interruptionMessage: String): Int = try {
        launcher.launch(request)
    } catch (_: InterruptedException) {
        interrupted(interruptionMessage)
    }

    private fun ensureNotInterrupted() {
        if (Thread.currentThread().isInterrupted) {
            throw UsageError("Interrupted while waiting for the empty Fluxzero dev environment to expose MCP.")
        }
    }

    private fun interrupted(message: String): Nothing {
        Thread.currentThread().interrupt()
        throw UsageError(message)
    }
}

internal class McpCommandExecutor(
    private val delegate: CommandExecutor = InheritedIoCommandExecutor()
) : CommandExecutor {
    override fun execute(command: List<String>, workingDirectory: Path, outputMode: OutputMode): Int =
        delegate.execute(command, workingDirectory, routedOutput(command, outputMode))

    override fun executeCleanup(command: List<String>, workingDirectory: Path, outputMode: OutputMode): Int =
        delegate.executeCleanup(command, workingDirectory, routedOutput(command, outputMode))

    override fun startDetached(command: List<String>, workingDirectory: Path, outputFile: Path): Long =
        delegate.startDetached(command, workingDirectory, outputFile)

    override fun releaseDetached(workingDirectory: Path) {
        delegate.releaseDetached(workingDirectory)
    }

    override fun <T> supervise(onShutdown: () -> Unit, action: () -> T): T =
        delegate.supervise(onShutdown, action)

    override fun <T> supervise(
        onShutdownStarted: () -> Unit,
        onShutdownComplete: () -> Unit,
        action: () -> T
    ): T = delegate.supervise(onShutdownStarted, onShutdownComplete, action)

    private fun routedOutput(command: List<String>, outputMode: OutputMode): OutputMode {
        if (outputMode != OutputMode.INHERIT) return outputMode
        val controlMain = command.indexOf(DevLaunchTarget.CONTROL.mainClass)
        val controlAction = if (controlMain >= 0) command.getOrNull(controlMain + 1) else null
        return if (DEV_SERVER_PREFLIGHT_MAIN in command || controlAction in setOf("wait", "stop")) {
            OutputMode.STDOUT_TO_STDERR
        } else {
            outputMode
        }
    }
}
