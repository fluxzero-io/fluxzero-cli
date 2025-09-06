package host.flux.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.sources.PropertiesValueSource
import host.flux.cli.commands.Init
import host.flux.cli.commands.Upgrade
import host.flux.cli.commands.Version
import host.flux.cli.commands.templates.Templates
import host.flux.cli.services.UpdateService

/**
 * Checks for updates on startup and then launches the CLI.
 */

class FluxCli : CliktCommand() {
    init {
        context {
            // Load default configuration options
            valueSources(
                PropertiesValueSource.from("~/.fluxzero/cli.properties"),
                PropertiesValueSource.from(".fluxzero/cli.properties"),
            )
        }
    }

    override fun run() = Unit
}


fun main(args: Array<String>) {
    try {
        val currentVersion = Version::class.java.`package`.implementationVersion ?: "dev"
        val updateInfo = UpdateService.checkForUpdates(currentVersion)
        if (updateInfo.hasUpdate) {
            System.err.println("A new version of fluxzero-cli is available: ${updateInfo.latestVersion} (current: $currentVersion)")
        }
        FluxCli()
            .subcommands(
                Init(),
                Version(),
                Upgrade(),
                Templates(),
            )
            .main(args)
    } catch (e: Exception) {
        System.err.println("Error: ${e.message}")
        kotlin.system.exitProcess(1)
    }
}
