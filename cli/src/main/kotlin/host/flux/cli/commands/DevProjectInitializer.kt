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
        val choice = select(
            "What would you like to do?",
            listOf(
                "Create a new project in the current folder",
                "Create a new project in a subfolder",
                "Cancel"
            )
        ) ?: return null
        return when (choice) {
            0 -> initialize(directory, useCurrentDirectory = true)
            1 -> initialize(directory, useCurrentDirectory = false)
            else -> null
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
        val templateIndex = select("Select a template:", templates.map { it.name })
            ?: throw IllegalArgumentException("A template must be selected")
        val defaultName = directory.fileName?.toString()?.lowercase() ?: "fluxzero-app"
        val name = read("Project name [$defaultName]: ")?.trim().orEmpty().ifEmpty { defaultName }
        val packageName = read("Package name [com.example.app]: ")?.trim().orEmpty()
            .ifEmpty { "com.example.app" }
        require(Regex("^[a-z][a-z0-9]*(?:\\.[a-z][a-z0-9]*)*$").matches(packageName)) {
            "Package name must contain lowercase letters, numbers, and dots"
        }
        val buildSystem = when (select("Select a build system:", listOf("Maven", "Gradle"))) {
            0 -> BuildSystem.MAVEN
            1 -> BuildSystem.GRADLE
            else -> throw IllegalArgumentException("A build system must be selected")
        }
        val result = scaffoldService.scaffoldProject(ScaffoldProject(
            template = templates[templateIndex].name,
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

    private fun select(question: String, options: List<String>): Int? = try {
        actualPrompt.select(question, options)
    } catch (_: org.jline.reader.EndOfFileException) {
        null
    }
}
