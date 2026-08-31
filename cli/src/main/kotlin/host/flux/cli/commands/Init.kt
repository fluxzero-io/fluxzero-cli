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
import host.flux.templates.services.ClasspathTemplateService
import host.flux.templates.services.FileSystemTemplateService
import host.flux.templates.models.ScaffoldProject
import host.flux.templates.models.BuildSystem
import java.nio.file.Paths

class Init(
    private val prompt: Prompt? = null,
    private val scaffoldService: ScaffoldService? = null
) : CliktCommand() {
    private val actualPrompt: Prompt by lazy { prompt ?: JLinePrompt() }

    override fun help(context: Context): String = "Create a new Fluxzero application"

    val template by option("--template", help = "Name of the template to generate your application with")

    val templatePath by option("--template-path", help = "Path to a directory containing templates or a ZIP file template")
        .path(mustExist = true, canBeDir = true, canBeFile = true)

    val dir by option(
        "--dir",
        help = "Parent directory for the named project, or the exact target with --in-place; defaults to the current working directory"
    )
        .path(mustExist = true, canBeFile = false, canBeDir = true, mustBeWritable = true)
        .default(Paths.get(""))

    val inPlace by option(
        "--in-place",
        help = "Generate directly in --dir (or the current directory) instead of creating a named child directory"
    ).flag(default = false)

    val name by option("--name", help = "Project name (will be normalized to lowercase, alphanumeric, hyphens, underscores)")

    val packageRegex = Regex("^[a-z][a-z0-9]*(?:\\.[a-z][a-z0-9]*)*$")
    val packageName by option("--package", help = "Java package name (e.g., com.example.myapp)").validate {
        require(packageRegex.matches(it)) {
            "Invalid package format: must be lowercase letters and dots (e.g., com.example.myapp)"
        }
    }

    val groupId by option("--group-id", help = "Maven/Gradle group ID (defaults to package name)")

    val artifactId by option("--artifact-id", help = "Maven/Gradle artifact ID (defaults to project name)")

    val applicationId by option("--application-id", help = "Fluxzero application ID to configure for package publishing")

    val description by option("--description", help = "Project description (defaults to 'A Flux application')")

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
        val actualScaffoldService = scaffoldService ?: run {
            val templateService = if (templatePath != null) {
                FileSystemTemplateService(templatePath!!)
            } else {
                ClasspathTemplateService()
            }
            ScaffoldService(templateService = templateService)
        }
        
        val finalTemplate = getTemplateName(actualScaffoldService)
        val finalName = name ?: promptForName()
        val finalPackage = packageName ?: promptForPackage()
        val finalBuildSystem = buildSystem?.let { 
            if (it == "maven") BuildSystem.MAVEN else BuildSystem.GRADLE 
        } ?: promptForBuildSystem()
        
        val request = ScaffoldProject(
            template = finalTemplate,
            name = finalName,
            outputDir = dir.toString().ifEmpty { null },
            inPlace = inPlace,
            initGit = initGit,
            packageName = finalPackage,
            groupId = groupId,
            artifactId = artifactId,
            applicationId = applicationId,
            description = description,
            buildSystem = finalBuildSystem
        )
        
        val result = actualScaffoldService.scaffoldProject(request)
        
        if (result.success) {
            echo(result.message)
        } else {
            echo("Error: ${result.message}", err = true)
        }
    }

    private fun promptForName(): String {
        val input = actualPrompt.readLine("Enter project name (will be normalized): ")?.trim()
        if (input.isNullOrBlank()) {
            throw RuntimeException("Cannot read input in non-interactive mode. Please specify --name parameter.")
        }
        return input
    }

    private fun promptForPackage(): String {
        while (true) {
            val input = actualPrompt.readLine("Enter package name (e.g., com.example.myapp) [com.example.app]: ")
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
        return when (actualPrompt.select("Please select a build system:", listOf("Maven", "Gradle"))) {
            0 -> BuildSystem.MAVEN
            1 -> BuildSystem.GRADLE
            else -> throw RuntimeException("Cannot read input in non-interactive mode. Please specify --build parameter.")
        }
    }

    private fun getTemplateName(scaffoldService: ScaffoldService): String {
        val templates = scaffoldService.listAvailableTemplates()

        fun promptForTemplate(): String {
            val choice = actualPrompt.select("Please select a template:", templates.map { it.name })
            return templates.getOrNull(choice)?.name
                ?: throw RuntimeException("Cannot read input in non-interactive mode. Please specify --template parameter.")
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
