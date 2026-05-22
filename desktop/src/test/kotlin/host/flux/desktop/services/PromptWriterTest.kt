package host.flux.desktop.services

import host.flux.desktop.model.AgentChoice
import host.flux.desktop.model.DesktopBuildSystem
import host.flux.desktop.model.GenerateProjectRequest
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class PromptWriterTest {
    @Test
    fun writesCustomPromptWithTrailingNewline() {
        val projectDir = createTempDirectory()
        val promptPath = PromptWriter().write(projectDir, request(firstPrompt = "Build this"), "1.75.1")

        assertEquals("Build this\n", promptPath.readText())
    }

    @Test
    fun writesDefaultPromptWithProjectMetadata() {
        val projectDir = createTempDirectory()
        val promptPath = PromptWriter().write(projectDir, request(firstPrompt = ""), "1.75.1")

        val prompt = promptPath.readText()
        assertContains(prompt, "Project: Demo App")
        assertContains(prompt, "Template: flux-basic-java")
        assertContains(prompt, "Detected Fluxzero SDK: 1.75.1")
    }

    private fun request(firstPrompt: String): GenerateProjectRequest {
        return GenerateProjectRequest(
            template = "flux-basic-java",
            name = "Demo App",
            outputBaseDir = "/tmp",
            packageName = "com.example.demo",
            groupId = "",
            artifactId = "",
            description = "",
            buildSystem = DesktopBuildSystem.MAVEN,
            initGit = true,
            firstPrompt = firstPrompt,
            agentChoice = AgentChoice.NONE
        )
    }
}
