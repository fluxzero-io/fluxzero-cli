package host.flux.desktop.services

import host.flux.desktop.model.AgentChoice
import host.flux.desktop.model.CommandResult
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class DeepLinkActionRunnerTest {
    @Test
    fun createLinkOpensExistingProjectInsteadOfRunningCli() {
        val tempDir = createTempDirectory()
        val projectDir = tempDir.resolve("demo-crm")
        Files.createDirectories(projectDir)
        projectDir.resolve("pom.xml").writeText("<project />")
        projectDir.resolve("START_PROMPT.md").writeText("Use the existing prompt")
        val paths = AppPaths(
            appDataDir = tempDir.resolve("app-data"),
            binDir = tempDir.resolve("bin"),
            cliExecutable = tempDir.resolve("bin").resolve("fz"),
            registryFile = tempDir.resolve("projects.json")
        )
        val cliRunner = FakeCommandRunner {
            _, _ -> error("CLI should not run when the target project already exists.")
        }
        val agentRunner = FakeCommandRunner { _, _ -> CommandResult(0, "") }
        val runner = DeepLinkActionRunner(
            paths = paths,
            cliRuntime = CliRuntimeService(paths, commandRunner = cliRunner),
            registry = ProjectRegistry(paths.registryFile),
            agentLauncher = AgentLauncher(
                platform = PlatformTarget(OperatingSystem.MACOS, CpuArchitecture.ARM64),
                commandRunner = agentRunner
            )
        )
        val link = FluxzeroCreateProjectLink(
            id = 1,
            name = "Demo CRM",
            template = null,
            location = tempDir.toString(),
            groupId = null,
            artifactId = null,
            packageName = null,
            description = null,
            buildSystem = null,
            initGit = true,
            prompt = null,
            agentChoice = AgentChoice.CLAUDE
        )

        runner.run(link)

        assertEquals(1, agentRunner.commands.size)
        val openedUrl = agentRunner.commands.single().last()
        assertContains(openedUrl, "claude-cli://open")
        assertContains(openedUrl, "cwd=${encoded(projectDir.toString())}")
        assertContains(openedUrl, "q=Use%20the%20existing%20prompt")
        assertEquals(emptyList(), cliRunner.commands)
    }

    private fun encoded(value: String): String {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20")
    }
}
