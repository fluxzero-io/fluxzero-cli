package host.flux.desktop.model

import kotlinx.serialization.Serializable

enum class DesktopBuildSystem(val cliValue: String, val label: String) {
    MAVEN("maven", "Maven"),
    GRADLE("gradle", "Gradle")
}

enum class AgentChoice(val label: String) {
    NONE("Do not open"),
    CODEX("Codex"),
    CLAUDE("Claude"),
    BOTH("Codex and Claude")
}

data class GenerateProjectRequest(
    val template: String,
    val name: String,
    val outputBaseDir: String,
    val packageName: String,
    val groupId: String,
    val artifactId: String,
    val description: String,
    val buildSystem: DesktopBuildSystem,
    val initGit: Boolean,
    val firstPrompt: String,
    val agentChoice: AgentChoice
)

data class GenerateProjectResult(
    val project: GeneratedProject,
    val commandOutput: String
)

@Serializable
data class RegistryState(
    val projects: List<GeneratedProject> = emptyList()
)

@Serializable
data class GeneratedProject(
    val id: String,
    val name: String,
    val path: String,
    val template: String,
    val buildSystem: String,
    val packageName: String,
    val generatedAt: String,
    val cliVersion: String? = null,
    val sdkVersion: String? = null,
    val promptPath: String? = null
)

data class CliStatus(
    val executablePath: String,
    val version: String?,
    val latestVersion: String?,
    val updated: Boolean,
    val message: String
)

data class CommandResult(
    val exitCode: Int,
    val output: String
) {
    val successful: Boolean get() = exitCode == 0
}
