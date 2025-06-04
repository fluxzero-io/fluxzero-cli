package host.flux.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.sources.PropertiesValueSource

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


fun main(args: Array<String>) = FluxCli()
    .subcommands(Init())
    .main(args)