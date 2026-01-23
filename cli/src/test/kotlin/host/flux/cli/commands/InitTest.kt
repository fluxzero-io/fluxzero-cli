package host.flux.cli.commands

import com.github.ajalt.clikt.testing.test
import host.flux.cli.prompt.Prompt
import host.flux.templates.services.ScaffoldService
import host.flux.templates.models.ScaffoldResult
import host.flux.templates.models.TemplateInfo
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import java.nio.file.Paths
import kotlin.test.Test

class InitTest {

    private lateinit var mockPrompt: Prompt
    private lateinit var mockInitService: ScaffoldService
    private lateinit var initCommand: Init

    @BeforeEach
    fun setup() {
        mockPrompt = mockk()
        mockInitService = mockk()

        every { mockInitService.listAvailableTemplates() } returns listOf(
            TemplateInfo("basic", "Basic template"),
            TemplateInfo("webapp", "Web application template"),
            TemplateInfo("cli", "CLI template")
        )
        every { mockInitService.scaffoldProject(any()) } returns ScaffoldResult(
            success = true,
            message = "Project initialized successfully",
            outputPath = "/test/path"
        )
    }

    @Test
    fun `uses provided template and name options`() {
        initCommand = Init(
            scaffoldService = mockInitService,
            prompt = mockPrompt
        )

        val result = initCommand.test(
            listOf(
                "--template", "webapp",
                "--name", "valid_name",
                "--package", "com.test.myapp",
                "--group-id", "com.test",
                "--artifact-id", "test-app",
                "--description", "Test application for unit tests",
                "--build", "maven",
                "--dir", Paths.get("").toAbsolutePath().toString()
            )
        )

        verify(exactly = 1) { mockInitService.scaffoldProject(any()) }
        Assertions.assertTrue(result.stdout.contains("Project initialized successfully"))
    }

    @Test
    fun `prompts for name when not provided`() {
        every { mockPrompt.readLine(match { it.contains("project name") }) } returns "prompted-name"
        every { mockPrompt.readLine(match { it.contains("Enter package") }) } returns "com.test.app"
        every { mockPrompt.readLine(match { it.contains("Enter choice") }) } returns "1"

        initCommand = Init(
            scaffoldService = mockInitService,
            prompt = mockPrompt
        )

        val result = initCommand.test(listOf("--template", "cli"))

        verify(exactly = 1) { mockPrompt.readLine(match { it.contains("project name") }) }
        verify(exactly = 1) { mockPrompt.readLine(match { it.contains("Enter package") }) }
        verify(exactly = 1) { mockPrompt.readLine(match { it.contains("Enter choice") }) }
        verify { mockInitService.scaffoldProject(any()) }
        Assertions.assertTrue(result.stdout.contains("Project initialized successfully"))
    }

    @Test
    fun `prompts for template when invalid template provided`() {
        every { mockPrompt.readLine(match { it.contains("Enter choice") }) } returns "1" andThen "2"
        every { mockPrompt.readLine(match { it.contains("Enter package") }) } returns "com.test.app"

        initCommand = Init(
            scaffoldService = mockInitService,
            prompt = mockPrompt
        )

        val result = initCommand.test(listOf("--template", "invalid-template", "--name", "valid_name"))

        verify(exactly = 2) { mockPrompt.readLine(match { it.contains("Enter choice") }) }
        verify { mockPrompt.readLine(match { it.contains("Enter package") }) }
        verify { mockInitService.scaffoldProject(any()) }
        Assertions.assertTrue(result.stdout.contains("Template 'invalid-template' does not exist."))
    }

    @Test
    fun `accepts any name format and passes to service`() {
        initCommand = Init(
            scaffoldService = mockInitService,
            prompt = mockPrompt
        )

        val result = initCommand.test(
            listOf(
                "--template", "webapp",
                "--name", "MyUpperCase@Name!",
                "--package", "com.test.myapp",
                "--build", "maven"
            )
        )

        verify(exactly = 1) {
            mockInitService.scaffoldProject(
                match { it.name == "MyUpperCase@Name!" }
            )
        }
        Assertions.assertTrue(result.stdout.contains("Project initialized successfully"))
    }

}