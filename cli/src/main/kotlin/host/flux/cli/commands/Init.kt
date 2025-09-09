package host.flux.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.validate
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.path
import host.flux.cli.prompt.JLinePrompt
import host.flux.cli.prompt.Prompt
import host.flux.templates.services.ScaffoldService
import host.flux.templates.models.ScaffoldProject
import host.flux.templates.models.BuildSystem
import java.nio.file.Paths

class Init(
    private val scaffoldService: ScaffoldService = ScaffoldService(),
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

    val packageRegex = Regex("^[a-z][a-z0-9]*(?:\\.[a-z][a-z0-9]*)*$")
    val packageName by option("--package", help = "Java package name (e.g., com.example.myapp)").validate {
        require(packageRegex.matches(it)) {
            "Invalid package format: must be lowercase letters and dots (e.g., com.example.myapp)"
        }
    }

    val groupId by option("--group-id", help = "Maven/Gradle group ID (defaults to package name)")

    val buildSystem by option("--build", help = "Build system to use").choice("maven", "gradle")

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
        val finalPackage = packageName ?: promptForPackage()
        val finalBuildSystem = buildSystem?.let { 
            if (it == "maven") BuildSystem.MAVEN else BuildSystem.GRADLE 
        } ?: promptForBuildSystem()
        
        val request = ScaffoldProject(
            template = finalTemplate,
            name = finalName,
            outputDir = dir.toString().ifEmpty { null },
            initGit = initGit,
            packageName = finalPackage,
            groupId = groupId,
            buildSystem = finalBuildSystem
        )
        
        val result = scaffoldService.scaffoldProject(request)
        
        if (result.success) {
            echo(result.message)
        } else {
            echo("Error: ${result.message}", err = true)
        }
    }

    private fun promptForName(): String {
        while (true) {
            val input = prompt.readLine("Enter name (max 50 chars, 0-9 a-z - _): ")?.trim()
            if (input == null) {
                throw RuntimeException("Cannot read input in non-interactive mode. Please specify --name parameter.")
            }
            if (nameRegex.matches(input)) {
                return input
            }
            echo("Invalid name format. Please use only digits, lowercase letters, '-' or '_', max length 50.")
        }
    }

    private fun promptForPackage(): String {
        while (true) {
            val input = prompt.readLine("Enter package name (e.g., com.example.myapp) [com.example.app]: ")
            if (input == null) {
                throw RuntimeException("Cannot read input in non-interactive mode. Please specify --package parameter.")
            }
            val finalInput = input.trim().ifEmpty { "com.example.app" }
            if (packageRegex.matches(finalInput)) {
                return finalInput
            }
            echo("Invalid package format. Please use lowercase letters and dots (e.g., com.example.myapp).")
        }
    }

    private fun promptForBuildSystem(): BuildSystem {
        while (true) {
            echo("Please select a build system:")
            echo("1) Maven")
            echo("2) Gradle")
            val input = prompt.readLine("Enter choice [1-2]: ")?.trim()
            when (input) {
                "1" -> return BuildSystem.MAVEN
                "2" -> return BuildSystem.GRADLE
                null -> throw RuntimeException("Cannot read input in non-interactive mode. Please specify --build parameter.")
                else -> echo("Invalid choice, try again.")
            }
        }
    }

    private fun getTemplateName(): String {
        val templates = scaffoldService.listAvailableTemplates()

        fun promptForTemplate(): String {
            while (true) {
                echo("Please select a template:")
                templates.forEachIndexed { i, t -> echo("${i + 1}) ${t.name}") }
                val input = prompt.readLine("Enter choice [1-${templates.size}]: ")?.trim()
                if (input == null) {
                    throw RuntimeException("Cannot read input in non-interactive mode. Please specify --template parameter.")
                }
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