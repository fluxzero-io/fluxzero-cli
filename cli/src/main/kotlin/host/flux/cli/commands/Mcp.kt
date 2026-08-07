package host.flux.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import host.flux.dev.DevLaunchRequest
import host.flux.dev.DevLaunchTarget
import host.flux.dev.DevLauncher
import host.flux.dev.DevServerLauncher
import host.flux.dev.DevStartupReadiness
import java.nio.file.Path

class Mcp(
    private val launcher: DevLauncher = DevServerLauncher()
) : CliktCommand() {
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

    override fun run() {
        val root = projectDirectory.toAbsolutePath().normalize()
        if (ensureDev) {
            val startExitCode = launcher.launch(
                DevLaunchRequest(
                    root, devServerVersion, DevLaunchTarget.SERVER, detached = true,
                    startupReadiness = DevStartupReadiness.AGENT_CONTROL_PLANE
                )
            )
            check(startExitCode == 0) {
                "Fluxzero dev environment could not be started (exit code $startExitCode)."
            }
        }
        val exitCode = launcher.launch(
            DevLaunchRequest(
                root,
                devServerVersion,
                DevLaunchTarget.MCP_STDIO,
                listOf("--project-dir", root.toString())
            )
        )
        check(exitCode == 0 || exitCode == 130 || exitCode == 143) {
            "Fluxzero MCP adapter exited with code $exitCode."
        }
    }
}
