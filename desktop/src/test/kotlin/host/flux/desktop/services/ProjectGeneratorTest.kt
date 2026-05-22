package host.flux.desktop.services

import host.flux.desktop.model.AgentChoice
import host.flux.desktop.model.CommandResult
import host.flux.desktop.model.DesktopBuildSystem
import host.flux.desktop.model.GenerateProjectRequest
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProjectGeneratorTest {
    @Test
    fun buildsCliInitCommand() {
        val generator = ProjectGenerator(
            cliExecutable = java.nio.file.Path.of("/bin/fz"),
            registry = ProjectRegistry(createTempDirectory().resolve("projects.json"))
        )

        val command = generator.buildCommand(request(outputBaseDir = "/tmp/projects"))

        assertEquals("/bin/fz", command[0])
        assertContains(command, "init")
        assertContains(command, "--template")
        assertContains(command, "flux-basic-java")
        assertContains(command, "--git")
        assertContains(command, "--package")
        assertContains(command, "com.example.demo")
    }

    @Test
    fun generatesWithFakeCliAndRegistersProject() {
        val tempDir = createTempDirectory()
        val registry = ProjectRegistry(tempDir.resolve("projects.json"))
        val runner = FakeCommandRunner { command, _ ->
            val outputBaseDir = java.nio.file.Path.of(command.valueAfter("--dir"))
            val name = command.valueAfter("--name")
            val projectDir = outputBaseDir.resolve(ProjectNameNormalizer.normalize(name))
            Files.createDirectories(projectDir)
            Files.writeString(
                projectDir.resolve("pom.xml"),
                """
                <project>
                  <properties>
                    <fluxzero.version>1.75.1</fluxzero.version>
                  </properties>
                </project>
                """.trimIndent()
            )
            CommandResult(0, "Successfully generated your project at '$projectDir'")
        }
        val generator = ProjectGenerator(
            cliExecutable = tempDir.resolve("fz"),
            registry = registry,
            commandRunner = runner
        )

        val result = generator.generate(request(outputBaseDir = tempDir.toString()), cliVersion = "1.2.3")

        assertEquals("demo-app", result.project.name)
        assertEquals("1.75.1", result.project.sdkVersion)
        assertTrue(tempDir.resolve("demo-app").resolve("START_PROMPT.md").exists())
        assertContains(tempDir.resolve("demo-app").resolve("START_PROMPT.md").readText(), "Ship it")
        assertEquals(result.project.path, registry.listProjects().single().path)
    }

    private fun request(outputBaseDir: String): GenerateProjectRequest {
        return GenerateProjectRequest(
            template = "flux-basic-java",
            name = "Demo App",
            outputBaseDir = outputBaseDir,
            packageName = "com.example.demo",
            groupId = "com.example",
            artifactId = "demo-app",
            description = "Demo",
            buildSystem = DesktopBuildSystem.MAVEN,
            initGit = true,
            firstPrompt = "Ship it",
            agentChoice = AgentChoice.NONE
        )
    }

    private fun List<String>.valueAfter(flag: String): String {
        val index = indexOf(flag)
        return get(index + 1)
    }
}
