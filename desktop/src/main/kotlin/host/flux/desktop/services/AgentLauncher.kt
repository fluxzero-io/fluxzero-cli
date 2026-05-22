package host.flux.desktop.services

import host.flux.desktop.model.AgentChoice
import host.flux.desktop.model.GeneratedProject
import java.awt.Desktop
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

data class AgentAvailability(
    val codexPath: Path?,
    val claudePath: Path?
) {
    val codexAvailable: Boolean get() = codexPath != null
    val claudeAvailable: Boolean get() = claudePath != null
}

data class AgentLaunchResult(
    val openedCodex: Boolean = false,
    val openedCodexDownload: Boolean = false,
    val openedClaude: Boolean = false
) {
    operator fun plus(other: AgentLaunchResult): AgentLaunchResult {
        return AgentLaunchResult(
            openedCodex = openedCodex || other.openedCodex,
            openedCodexDownload = openedCodexDownload || other.openedCodexDownload,
            openedClaude = openedClaude || other.openedClaude
        )
    }
}

class AgentLauncher(
    private val platform: PlatformTarget = PlatformDetector.detect(),
    private val commandRunner: CommandRunner = ProcessCommandRunner()
) {
    fun detectAvailability(): AgentAvailability {
        val home = System.getProperty("user.home")
        return AgentAvailability(
            codexPath = findExecutable(
                "codex",
                macFallbacks = listOf(
                    "/Applications/Codex.app/Contents/Resources/codex",
                    "$home/Applications/Codex.app/Contents/Resources/codex"
                )
            ),
            claudePath = findExecutable("claude")
        )
    }

    fun launchSelected(choice: AgentChoice, project: GeneratedProject, prompt: String): AgentLaunchResult {
        return launchPath(choice, project.path, prompt)
    }

    fun launchPath(choice: AgentChoice, projectPath: String, prompt: String): AgentLaunchResult {
        return when (choice) {
            AgentChoice.NONE -> AgentLaunchResult()
            AgentChoice.CODEX -> launchCodex(projectPath, prompt)
            AgentChoice.CLAUDE -> launchClaude(projectPath, prompt)
            AgentChoice.BOTH -> {
                launchCodex(projectPath, prompt) + launchClaude(projectPath, prompt)
            }
        }
    }

    fun launchCodex(projectPath: String, prompt: String = ""): AgentLaunchResult {
        if (!detectAvailability().codexAvailable) {
            openUrl(codexDownloadUrl())
            return AgentLaunchResult(openedCodexDownload = true)
        }
        openUrl(buildCodexDeepLink(projectPath, prompt))
        return AgentLaunchResult(openedCodex = true)
    }

    fun launchClaude(projectPath: String, prompt: String): AgentLaunchResult {
        val url = buildClaudeDeepLink(projectPath, prompt)
        openUrl(url)
        return AgentLaunchResult(openedClaude = true)
    }

    fun buildCodexDeepLink(projectPath: String, prompt: String): String {
        val encodedPath = encode(projectPath)
        val encodedPrompt = encode(prompt.ifBlank { "Open START_PROMPT.md and help me continue from there." })
        return "codex://new?path=$encodedPath&prompt=$encodedPrompt"
    }

    fun codexDownloadUrl(): String {
        return when (platform.os) {
            OperatingSystem.MACOS -> when (platform.arch) {
                CpuArchitecture.ARM64 -> "https://persistent.oaistatic.com/codex-app-prod/Codex.dmg"
                CpuArchitecture.AMD64 -> "https://persistent.oaistatic.com/codex-app-prod/Codex-latest-x64.dmg"
            }
            OperatingSystem.WINDOWS -> "https://get.microsoft.com/installer/download/9PLM9XGG6VKS?cid=website_cta_psi"
            OperatingSystem.LINUX -> "https://openai.com/codex/"
        }
    }

    fun openProjectFolder(projectPath: String) {
        val file = Path.of(projectPath).toFile()
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
            Desktop.getDesktop().open(file)
            return
        }
        when (platform.os) {
            OperatingSystem.MACOS -> commandRunner.run(listOf("open", projectPath))
            OperatingSystem.WINDOWS -> commandRunner.run(listOf("explorer", projectPath))
            OperatingSystem.LINUX -> commandRunner.run(listOf("xdg-open", projectPath))
        }
    }

    fun buildClaudeDeepLink(projectPath: String, prompt: String): String {
        val encodedPath = encode(projectPath)
        val encodedPrompt = encode(prompt.ifBlank { "Open START_PROMPT.md and help me continue from there." })
        return "claude-cli://open?cwd=$encodedPath&q=$encodedPrompt"
    }

    private fun openUrl(url: String) {
        when (platform.os) {
            OperatingSystem.MACOS -> commandRunner.run(listOf("open", url), timeout = Duration.ofSeconds(15))
            OperatingSystem.WINDOWS -> commandRunner.run(listOf("rundll32", "url.dll,FileProtocolHandler", url), timeout = Duration.ofSeconds(15))
            OperatingSystem.LINUX -> commandRunner.run(listOf("xdg-open", url), timeout = Duration.ofSeconds(15))
        }
    }

    private fun findExecutable(command: String, macFallbacks: List<String> = emptyList()): Path? {
        val pathSeparator = System.getProperty("path.separator")
        val pathEntries = (System.getenv("PATH") ?: "")
            .split(pathSeparator)
            .filter { it.isNotBlank() }
        val executableNames = if (platform.os == OperatingSystem.WINDOWS && !command.endsWith(".exe")) {
            listOf("$command.exe", command)
        } else {
            listOf(command)
        }
        for (entry in pathEntries) {
            for (name in executableNames) {
                val candidate = Path.of(entry).resolve(name)
                if (Files.isExecutable(candidate)) {
                    return candidate
                }
            }
        }
        if (platform.os == OperatingSystem.MACOS) {
            for (fallback in macFallbacks) {
                val candidate = Path.of(fallback)
                if (Files.isExecutable(candidate)) {
                    return candidate
                }
            }
        }
        return null
    }

    private fun encode(value: String): String {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
    }
}
