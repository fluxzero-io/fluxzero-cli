package host.flux.desktop.services

import host.flux.desktop.model.GenerateProjectRequest
import java.nio.file.Files
import java.nio.file.Path

class PromptWriter {
    fun write(projectDir: Path, request: GenerateProjectRequest, sdkVersion: String?): Path {
        val promptPath = projectDir.resolve(PROMPT_FILE)
        val prompt = request.firstPrompt.trim().ifBlank {
            defaultPrompt(request, sdkVersion)
        }
        Files.writeString(promptPath, prompt.ensureMarkdownFileEnding())
        return promptPath
    }

    private fun defaultPrompt(request: GenerateProjectRequest, sdkVersion: String?): String {
        return buildString {
            appendLine("# Start Prompt")
            appendLine()
            appendLine("You are working in a freshly generated Fluxzero project.")
            appendLine()
            appendLine("- Project: ${request.name}")
            appendLine("- Template: ${request.template}")
            appendLine("- Build system: ${request.buildSystem.label}")
            appendLine("- Package: ${request.packageName}")
            if (sdkVersion != null) {
                appendLine("- Detected Fluxzero SDK: $sdkVersion")
            }
            appendLine()
            appendLine("First inspect the generated structure, then help me turn this template into a working application.")
        }
    }

    private fun String.ensureMarkdownFileEnding(): String = trimEnd() + "\n"

    companion object {
        const val PROMPT_FILE = "START_PROMPT.md"
    }
}
