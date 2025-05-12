package host.flux.cli

import com.github.ajalt.clikt.core.*
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.switch
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.sources.PropertiesValueSource

class FluxCli : CliktCommand() {
    override fun run() = Unit
}

class Init : CliktCommand() {

    init {
        context {
            // Load default configuration options
            valueSources(
                PropertiesValueSource.from("~/.flux/cli.properties"),
                PropertiesValueSource.from(".flux/cli.properties"),
            )
        }
    }

    override fun help(context: Context): String = "Initialize a new Flux application"

    val packageName by option("--package", help = "Name of the package (com.example.your.application)")
    val groupName by option("--group", help = "Application group name (com.example.business)")
    val artifactName by option("--name", help = "Application name (your-application)")
    val language by option(help = "Which language do you prefer to use? (defaults to kotlin)")
        .choice("kotlin", "java")
        .default("kotlin")

    val anonymousMetrics by option(help = "Allow collection of anonymous usage data by Flux").switch(
        "--enable-usage" to "enable",
        "--disable-usage" to "disable"
    ).default("unspecified")

    override fun run() {
        echo (anonymousMetrics)
    }
}

fun main(args: Array<String>) = FluxCli()
    .subcommands(Init())
    .main(args)