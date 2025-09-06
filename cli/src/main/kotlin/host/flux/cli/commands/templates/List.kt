package host.flux.cli.commands.templates

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import host.flux.templates.services.TemplateService

class List(
    private val templateService: TemplateService = TemplateService()
) : CliktCommand() {

    override fun help(context: Context): String = "List all available templates"

    override fun run() {
        val templates = templateService.listTemplates()
        templates.forEach { template ->
            echo("${template.name}: ${template.description}")
        }
    }
}