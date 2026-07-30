package host.flux.cli.commands

import host.flux.cli.prompt.JLinePrompt
import host.flux.cli.prompt.Prompt
import host.flux.templates.models.BuildSystem
import host.flux.templates.models.ScaffoldProject
import host.flux.templates.services.ClasspathTemplateService
import host.flux.templates.services.ScaffoldService
import java.nio.file.Files
import java.nio.file.Path

fun interface DevProjectInitializer {
    fun initialize(directory: Path): Path?
}

class InteractiveDevProjectInitializer(
    private val prompt: Prompt? = null,
    private val scaffoldService: ScaffoldService = ScaffoldService(ClasspathTemplateService()),
    private val output: (String) -> Unit = { println(it) }
) : DevProjectInitializer {
    private val actualPrompt by lazy { prompt ?: JLinePrompt() }

    override fun initialize(directory: Path): Path? {
        output("No Maven or Gradle project was found in '$directory'.")
        output("")
        output("What would you like to do?")
        output("")
        output("1) Create a new project in the current folder")
        output("2) Create a new project in a subfolder")
        output("3) Cancel")
        val choice = read("Choice [1-3]: ")?.trim()
        return when (choice) {
            "1" -> initialize(directory, useCurrentDirectory = true)
            "2" -> initialize(directory, useCurrentDirectory = false)
            "3", "", null -> null
            else -> throw IllegalArgumentException("Choose 1, 2, or 3")
        }
    }

    private fun initialize(directory: Path, useCurrentDirectory: Boolean): Path? {
        if (useCurrentDirectory && Files.list(directory).use { it.findAny().isPresent }) {
            throw IllegalArgumentException(
                "The current folder is not empty. Choose a subfolder or run fz dev from an empty folder."
            )
        }
        val templates = scaffoldService.listAvailableTemplates()
        output("")
        output("Select a template:")
        templates.forEachIndexed { index, template -> output("${index + 1}) ${template.name}") }
        val templateIndex = read("Choice [1-${templates.size}]: ")?.trim()?.toIntOrNull()
            ?: throw IllegalArgumentException("A template must be selected")
        require(templateIndex in 1..templates.size) { "Choose a template between 1 and ${templates.size}" }
        val defaultName = directory.fileName?.toString()?.lowercase() ?: "fluxzero-app"
        val name = read("Project name [$defaultName]: ")?.trim().orEmpty().ifEmpty { defaultName }
        val packageName = read("Package name [com.example.app]: ")?.trim().orEmpty()
            .ifEmpty { "com.example.app" }
        require(Regex("^[a-z][a-z0-9]*(?:\\.[a-z][a-z0-9]*)*$").matches(packageName)) {
            "Package name must contain lowercase letters, numbers, and dots"
        }
        output("Select a build system:")
        output("1) Maven")
        output("2) Gradle")
        val buildSystem = when (read("Choice [1-2]: ")?.trim()) {
            "1" -> BuildSystem.MAVEN
            "2" -> BuildSystem.GRADLE
            else -> throw IllegalArgumentException("Choose Maven or Gradle")
        }
        val result = scaffoldService.scaffoldProject(ScaffoldProject(
            template = templates[templateIndex - 1].name,
            name = name,
            outputDir = directory.toString(),
            packageName = packageName,
            buildSystem = buildSystem,
            useOutputDirectory = useCurrentDirectory
        ))
        if (!result.success || result.outputPath == null) {
            throw IllegalStateException(result.message)
        }
        output(result.message)
        return Path.of(result.outputPath).toAbsolutePath().normalize()
    }

    private fun read(message: String): String? = try {
        actualPrompt.readLine(message)
    } catch (_: org.jline.reader.EndOfFileException) {
        null
    }
}
