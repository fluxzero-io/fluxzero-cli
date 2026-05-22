package host.flux.desktop.services

import host.flux.desktop.model.GeneratedProject
import host.flux.desktop.model.GenerateProjectRequest
import host.flux.desktop.model.GenerateProjectResult
import host.flux.projectfiles.SdkVersionDetector
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.UUID

class ProjectGenerator(
    private val cliExecutable: Path,
    private val registry: ProjectRegistry,
    private val promptWriter: PromptWriter = PromptWriter(),
    private val commandRunner: CommandRunner = ProcessCommandRunner()
) {
    fun generate(request: GenerateProjectRequest, cliVersion: String?): GenerateProjectResult {
        val normalizedName = ProjectNameNormalizer.normalize(request.name)
        require(normalizedName.isNotBlank()) { "Project name must contain at least one letter or number." }

        val outputBaseDir = Path.of(request.outputBaseDir).toAbsolutePath().normalize()
        Files.createDirectories(outputBaseDir)
        val projectDir = outputBaseDir.resolve(normalizedName)

        val command = buildCommand(request)
        val result = commandRunner.run(command, timeout = Duration.ofMinutes(10))
        if (!result.successful) {
            error("Fluxzero CLI failed with exit code ${result.exitCode}.\n${result.output}")
        }
        if (result.output.lineSequence().any { it.trimStart().startsWith("Error:") } || !Files.isDirectory(projectDir)) {
            error("Fluxzero CLI did not generate the expected project directory.\n${result.output}")
        }

        val sdkVersion = SdkVersionDetector.detect(projectDir)
        val promptPath = promptWriter.write(projectDir, request, sdkVersion)
        val project = GeneratedProject(
            id = stableProjectId(projectDir),
            name = normalizedName,
            path = projectDir.toString(),
            template = request.template,
            buildSystem = request.buildSystem.cliValue,
            packageName = request.packageName,
            generatedAt = Instant.now().toString(),
            cliVersion = cliVersion,
            sdkVersion = sdkVersion,
            promptPath = promptPath.toString()
        )
        registry.saveProject(project)
        return GenerateProjectResult(project, result.output)
    }

    fun buildCommand(request: GenerateProjectRequest): List<String> {
        return buildList {
            add(cliExecutable.toString())
            add("init")
            add("--template")
            add(request.template)
            add("--name")
            add(request.name)
            add("--dir")
            add(Path.of(request.outputBaseDir).toAbsolutePath().normalize().toString())
            add("--package")
            add(request.packageName)
            add("--build")
            add(request.buildSystem.cliValue)
            request.groupId.trim().takeIf { it.isNotBlank() }?.let {
                add("--group-id")
                add(it)
            }
            request.artifactId.trim().takeIf { it.isNotBlank() }?.let {
                add("--artifact-id")
                add(it)
            }
            request.description.trim().takeIf { it.isNotBlank() }?.let {
                add("--description")
                add(it)
            }
            if (request.initGit) {
                add("--git")
            }
        }
    }

    private fun stableProjectId(projectDir: Path): String {
        return UUID.nameUUIDFromBytes(projectDir.toAbsolutePath().normalize().toString().toByteArray()).toString()
    }
}
