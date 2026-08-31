package host.flux.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.output.HelpFormatter
import com.github.ajalt.clikt.output.MordantHelpFormatter
import com.github.ajalt.clikt.parameters.options.versionOption
import com.github.ajalt.clikt.sources.PropertiesValueSource
import com.github.ajalt.mordant.rendering.Widget
import com.github.ajalt.mordant.table.verticalLayout
import host.flux.cli.commands.Init
import host.flux.cli.commands.Dev
import host.flux.cli.commands.Mcp
import host.flux.cli.commands.Upgrade
import host.flux.cli.commands.Version
import host.flux.cli.commands.templates.Templates
import host.flux.cli.services.UpdateService
import host.flux.cli.services.VersionService

/**
 * Checks for updates on startup and then launches the CLI.
 */

class FluxCli : CliktCommand(name = "fz") {
    init {
        versionOption(
            VersionService.getCurrentVersion(),
            names = setOf("-V", "--version"),
            help = "Show the installed version and exit",
            message = { it },
        )
        context {
            // Load default configuration options
            valueSources(
                PropertiesValueSource.from("~/.fluxzero/cli.properties"),
                PropertiesValueSource.from(".fluxzero/cli.properties"),
            )
            helpFormatter = { context -> CommandFirstHelpFormatter(context) }
        }
    }

    override fun help(context: Context): String =
        "Build, run, and manage Fluxzero applications"

    override fun run() = Unit
}


fun main(args: Array<String>) {
    try {
        val currentVersion = Version::class.java.`package`.implementationVersion ?: "dev"
        if (shouldCheckForUpdates(args) && currentVersion != "dev" && !currentVersion.endsWith("SNAPSHOT")) {
            val updateInfo = UpdateService.checkForUpdates(currentVersion)
            if (updateInfo.hasUpdate) {
                System.err.println(
                    "A new version of the Fluxzero CLI is available: " +
                        "${updateInfo.latestVersion} (current: $currentVersion)"
                )
            }
        }
        FluxCli()
            .subcommands(
                Init(),
                Dev(),
                Templates(),
                Upgrade(),
                Mcp(),
                Version(),
            )
            .main(args)
    } catch (e: Exception) {
        System.err.println("Error: ${e.actionableMessage()}")
        kotlin.system.exitProcess(1)
    }
}

internal fun shouldCheckForUpdates(args: Array<String>): Boolean = args.firstOrNull() !in setOf(
    "-V",
    "--version",
    "version",
    "upgrade",
)

private class CommandFirstHelpFormatter(context: Context) : MordantHelpFormatter(context) {
    override fun renderParameters(parameters: List<HelpFormatter.ParameterHelp>): Widget {
        val (commands, otherParameters) = parameters.partition {
            it is HelpFormatter.ParameterHelp.Subcommand
        }
        if (commands.isEmpty() || otherParameters.isEmpty()) return super.renderParameters(parameters)
        return verticalLayout {
            spacing = 1
            cell(super.renderParameters(commands))
            cell(super.renderParameters(otherParameters))
        }
    }
}

internal fun Throwable.actionableMessage(): String = generateSequence(this) { it.cause }
    .mapNotNull { cause -> cause.message?.takeIf(String::isNotBlank) }
    .firstOrNull()
    ?: javaClass.simpleName
