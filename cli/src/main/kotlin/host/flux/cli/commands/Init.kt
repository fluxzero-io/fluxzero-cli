package host.flux.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.validate
import com.github.ajalt.clikt.parameters.types.path
import host.flux.cli.prompt.JLinePrompt
import host.flux.cli.prompt.Prompt
import host.flux.templates.services.InitializationService
import host.flux.templates.models.InitRequest
import java.nio.file.Paths
import kotlin.io.path.absolute

class Init(
    private val initializationService: InitializationService = InitializationService(),
    private val prompt: Prompt = JLinePrompt()
) : CliktCommand() {

    override fun help(context: Context): String = "Initialize a new Flux application"

    val template by option("--template", help = "Name of the template to generate your application with")

    val dir by option(
        "--dir",
        help = "The directory in which to create your application; defaults to the current working directory"
    )
        .path(mustExist = true, canBeFile = false, canBeDir = true, mustBeWritable = true)
        .default(Paths.get(""))

    val nameRegex = Regex("^[0-9a-z-_]{1,50}$")
    val name by option("--name", help = "Name (max 50 chars, 0-9 a-z - _)").validate {
        require(nameRegex.matches(it)) {
            "Invalid name format: must be 1-50 chars of digits, lowercase letters, '-' or '_' only"
        }
    }

    val initGit by option(
        "--git",
        help = "Initialize a Git repository in the generated project directory"
    ).flag(default = false)

//
//    val anonymousMetrics by option(help = "Allow collection of anonymous usage data by Flux").switch(
//        "--enable-usage" to "enable",
//        "--disable-usage" to "disable"
//    ).default("unspecified")

    override fun run() {
        val finalTemplate = getTemplateName()
        val finalName = name ?: promptForName()
        
        val request = InitRequest(
            template = finalTemplate,
            name = finalName,
            outputDir = if (dir.toString().isEmpty()) null else dir.toString(),
            initGit = initGit
        )
        
        val result = initializationService.initializeProject(request)
        
        if (result.success) {
            echo(result.message)
        } else {
            echo("Error: ${result.message}", err = true)
        }
    }

    private fun promptForName(): String {
        while (true) {
            val input = prompt.readLine("Enter name (max 50 chars, 0-9 a-z - _): ").trim()
            if (nameRegex.matches(input)) {
                return input
            }
            echo("Invalid name format. Please use only digits, lowercase letters, '-' or '_', max length 50.")
        }
    }

    private fun getTemplateName(): String {
        val templates = initializationService.listAvailableTemplates()

        fun promptForTemplate(): String {
            while (true) {
                echo("Please select a template:")
                templates.forEachIndexed { i, t -> echo("${i + 1}) ${t.name}") }
                val input = prompt.readLine("Enter choice [1-${templates.size}]: ").trim()
                val choice = input.toIntOrNull()
                if (choice != null && choice in 1..templates.size) {
                    return templates[choice - 1].name
                }
                echo("Invalid choice, try again.")
            }
        }

        val finalTemplate = if (template == null)
            promptForTemplate()
        else if (templates.any { it.name == template })
            template!!
        else {
            echo("Template '${template}' does not exist.")
            promptForTemplate()
        }

        return finalTemplate
    }


}