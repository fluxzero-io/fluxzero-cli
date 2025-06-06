package host.flux.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.sources.PropertiesValueSource
import host.flux.cli.commands.Init
import host.flux.cli.commands.Version
import host.flux.cli.commands.Upgrade
import host.flux.cli.UpdateChecker

/**
 * Checks for updates on startup and then launches the CLI.
 */

class FluxCli : CliktCommand() {
    init {
        context {
            // Load default configuration options
            valueSources(
                PropertiesValueSource.from("~/.flux/cli.properties"),
                PropertiesValueSource.from(".flux/cli.properties"),
            )
        }
    }
    override fun run() = Unit
}


fun main(args: Array<String>) {
    val currentVersion = Version::class.java.`package`.implementationVersion ?: "dev"
    UpdateChecker.notifyIfNewVersion(currentVersion) { println(it) }
    FluxCli()
        .subcommands(
            Init(),
            Version(),
            Upgrade(),
        )
        .main(args)
}
