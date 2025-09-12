package host.flux.cli.commands.templates

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import host.flux.templates.services.TemplateService
import host.flux.templates.services.ClasspathTemplateService

class List(
    private val templateService: TemplateService = ClasspathTemplateService()
) : CliktCommand() {

    override fun help(context: Context): String = "List all available templates"

    override fun run() {
        val templates = templateService.listTemplates()
        templates.forEach { template ->
            echo("${template.name}${if (template.description.isNotEmpty()) ": ${template.description}" else ""}")
        }
    }
}