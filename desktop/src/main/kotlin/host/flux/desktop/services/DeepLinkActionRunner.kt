package host.flux.desktop.services

import host.flux.desktop.model.AgentChoice
import host.flux.desktop.model.DesktopBuildSystem
import host.flux.desktop.model.GenerateProjectRequest
import java.nio.file.Files
import java.nio.file.Path

class DeepLinkActionRunner(
    private val paths: AppPaths = AppPaths.detect(),
    private val cliRuntime: CliRuntimeService = CliRuntimeService(paths),
    private val registry: ProjectRegistry = ProjectRegistry(paths.registryFile),
    private val agentLauncher: AgentLauncher = AgentLauncher()
) {
    fun run(link: FluxzeroDirectLink): AgentLaunchResult {
        return when (link) {
            is FluxzeroOpenAgentLink -> agentLauncher.launchPath(link.agentChoice, link.path, link.prompt.orEmpty())
            is FluxzeroCreateProjectLink -> createProjectAndLaunch(link)
        }
    }

    private fun createProjectAndLaunch(link: FluxzeroCreateProjectLink): AgentLaunchResult {
        val projectName = link.name?.takeIf { it.isNotBlank() }
            ?: error("Project name is required for fluxzero://create links.")
        val outputBaseDir = Path.of(
            link.location?.takeIf { it.isNotBlank() }
                ?: Path.of(System.getProperty("user.home"), "FluxzeroProjects").toString()
        ).toAbsolutePath().normalize()
        val normalizedName = ProjectNameNormalizer.normalize(projectName)
        require(normalizedName.isNotBlank()) { "Project name must contain at least one letter or number." }
        val projectDir = outputBaseDir.resolve(normalizedName)
        if (isNonEmptyDirectory(projectDir)) {
            return launchExistingProject(projectDir, link)
        }

        val artifactId = link.artifactId?.takeIf { it.isNotBlank() } ?: artifactIdFromProjectName(projectName)
        val groupId = link.groupId?.takeIf { it.isNotBlank() } ?: DEFAULT_GROUP_ID
        val packageName = link.packageName?.takeIf { it.isNotBlank() } ?: defaultPackageName(groupId, artifactId)
        val template = link.template?.takeIf { it.isNotBlank() } ?: DEFAULT_TEMPLATE
        val cliStatus = cliRuntime.ensureLatestCli()
        val request = GenerateProjectRequest(
            template = template,
            name = projectName,
            outputBaseDir = outputBaseDir.toString(),
            packageName = packageName,
            groupId = groupId,
            artifactId = artifactId,
            description = link.description?.takeIf { it.isNotBlank() } ?: "A Fluxzero application",
            buildSystem = link.buildSystem ?: defaultBuildSystemForTemplate(template),
            initGit = link.initGit,
            firstPrompt = link.prompt.orEmpty(),
            agentChoice = link.agentChoice
        )
        val generator = ProjectGenerator(Path.of(cliStatus.executablePath), registry)
        val generated = try {
            generator.generate(request, cliStatus.version).project
        } catch (e: Exception) {
            if (isNonEmptyDirectory(projectDir)) {
                return launchExistingProject(projectDir, link)
            }
            throw e
        }
        val prompt = generated.promptPath
            ?.let(Path::of)
            ?.takeIf(Files::isRegularFile)
            ?.let(Files::readString)
            ?: link.prompt.orEmpty()
        return agentLauncher.launchPath(link.agentChoice, generated.path, prompt)
    }

    private fun launchExistingProject(projectDir: Path, link: FluxzeroCreateProjectLink): AgentLaunchResult {
        return agentLauncher.launchPath(
            choice = link.agentChoice,
            projectPath = projectDir.toString(),
            prompt = promptForExistingProject(projectDir, link.prompt)
        )
    }

    private fun promptForExistingProject(projectDir: Path, prompt: String?): String {
        return prompt?.takeIf { it.isNotBlank() }
            ?: projectDir.resolve("START_PROMPT.md")
                .takeIf(Files::isRegularFile)
                ?.let(Files::readString)
            ?: ""
    }

    private fun isNonEmptyDirectory(path: Path): Boolean {
        if (!Files.isDirectory(path)) {
            return false
        }
        return Files.newDirectoryStream(path).use { entries ->
            entries.iterator().hasNext()
        }
    }

    private fun defaultBuildSystemForTemplate(template: String): DesktopBuildSystem {
        return if (template.contains("kotlin", ignoreCase = true)) {
            DesktopBuildSystem.GRADLE
        } else {
            DesktopBuildSystem.MAVEN
        }
    }

    private fun artifactIdFromProjectName(name: String): String {
        return name
            .lowercase()
            .replace(Regex("[^a-z0-9\\s_-]"), "")
            .replace(Regex("[\\s_]+"), "-")
            .replace(Regex("-+"), "-")
            .trim('-')
            .ifBlank { "app" }
    }

    private fun defaultPackageName(groupId: String, artifactId: String): String {
        val packageSuffix = artifactId
            .lowercase()
            .replace(Regex("[^a-z0-9]"), "")
            .ifBlank { "app" }
        return packageNameFromInput("${groupId.ifBlank { DEFAULT_GROUP_ID }}.$packageSuffix")
    }

    private fun packageNameFromInput(value: String): String {
        return value
            .lowercase()
            .replace(Regex("[^a-z0-9.]"), "")
            .replace(Regex("\\.+"), ".")
            .trim('.')
    }

    companion object {
        private const val DEFAULT_TEMPLATE = "flux-basic-java"
        private const val DEFAULT_GROUP_ID = "com.example"
    }
}
