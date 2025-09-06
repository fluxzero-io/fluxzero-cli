package host.flux.cli.commands.templates

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands

class Templates : CliktCommand() {
    init {
        subcommands(List())
    }

    override fun help(context: Context): String = "Manage Flux templates"

    override fun run() = Unit
}