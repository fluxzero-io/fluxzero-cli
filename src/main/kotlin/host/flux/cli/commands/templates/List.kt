package host.flux.cli.commands.templates

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import host.flux.cli.template.TemplateExtractor

class List(
    private val templateExtractor: TemplateExtractor = TemplateExtractor()
) : CliktCommand() {

    override fun help(context: Context): String = "List all available templates"

    override fun run() {
        val templates = templateExtractor.listTemplates()
        templates.forEach { template ->
            echo(template)
        }
    }
}