package host.flux.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import host.flux.dev.DevLaunchRequest
import host.flux.dev.DevLaunchTarget
import host.flux.dev.DevLauncher
import host.flux.dev.DevServerLauncher
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

    override fun run() {
        val root = projectDirectory.toAbsolutePath().normalize()
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
