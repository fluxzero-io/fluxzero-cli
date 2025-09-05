package host.flux.cli.commands

import com.github.ajalt.clikt.core.BadParameterValue
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.testing.test
import host.flux.cli.commands.Init
import host.flux.cli.prompt.Prompt
import host.flux.templates.services.InitializationService
import host.flux.templates.models.InitRequest
import host.flux.templates.models.InitResult
import host.flux.templates.models.TemplateInfo
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import java.nio.file.Paths
import kotlin.test.Test

class InitTest {

    private lateinit var mockPrompt: Prompt
    private lateinit var mockInitService: InitializationService
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
        every { mockInitService.initializeProject(any()) } returns InitResult(
            success = true,
            message = "Project initialized successfully",
            outputPath = "/test/path"
        )
    }

    @Test
    fun `uses provided template and name options`() {
        initCommand = Init(
            initializationService = mockInitService,
            prompt = mockPrompt
        )

        val result = initCommand.test(
            listOf(
                "--template", "webapp",
                "--name", "valid_name",
                "--dir", Paths.get("").toAbsolutePath().toString()
            )
        )

        verify(exactly = 1) { mockInitService.initializeProject(any()) }
        Assertions.assertTrue(result.stdout.contains("Project initialized successfully"))
    }

    @Test
    fun `prompts for name when not provided`() {
        every { mockPrompt.readLine(any()) } returns "prompted-name"

        initCommand = Init(
            initializationService = mockInitService,
            prompt = mockPrompt
        )

        val result = initCommand.test(listOf("--template", "cli"))

        verify(exactly = 1) { mockPrompt.readLine(match { it.contains("Enter name") }) }
        verify { mockInitService.initializeProject(any()) }
        Assertions.assertTrue(result.stdout.contains("Project initialized successfully"))
    }

    @Test
    fun `prompts for template when invalid template provided`() {
        every { mockPrompt.readLine(any()) } returns "1"

        initCommand = Init(
            initializationService = mockInitService,
            prompt = mockPrompt
        )

        val result = initCommand.test(listOf("--template", "invalid-template", "--name", "valid_name"))

        verify { mockPrompt.readLine(match { it.contains("Enter choice") }) }
        verify { mockInitService.initializeProject(any()) }
        Assertions.assertTrue(result.stdout.contains("Template 'invalid-template' does not exist."))
    }

    @Test
    fun `fails with invalid name option`() {
        initCommand = Init(
            initializationService = mockInitService,
            prompt = mockPrompt
        )

        val exception = Assertions.assertThrows(BadParameterValue::class.java) {
            initCommand.parse(listOf("--name", "INVALID!"))
        }
        Assertions.assertTrue(exception.message!!.contains("Invalid name format"))
    }

}