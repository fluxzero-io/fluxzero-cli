package host.flux.cli.commands

import com.github.ajalt.clikt.core.BadParameterValue
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.testing.test
import host.flux.cli.commands.Init
import host.flux.cli.prompt.Prompt
import host.flux.cli.template.TemplateExtractor
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
    private lateinit var mockExtractor: TemplateExtractor
    private lateinit var initCommand: Init

    @BeforeEach
    fun setup() {
        mockPrompt = mockk()
        mockExtractor = mockk()

        every { mockExtractor.listTemplates() } returns listOf("basic", "webapp", "cli")
        every { mockExtractor.extract(any(), any()) } just Runs
    }

    @Test
    fun `uses provided template and name options`() {
        initCommand = Init(
            templateExtractor = mockExtractor,
            prompt = mockPrompt
        )

        val result = initCommand.test(
            listOf(
                "--template", "webapp",
                "--name", "valid_name",
                "--dir", Paths.get("").toAbsolutePath().toString()
            )
        )

        verify(exactly = 1) { mockExtractor.extract("webapp", any()) }
        Assertions.assertTrue(result.stdout.contains("Successfully generated"))
    }

    @Test
    fun `prompts for name when not provided`() {
        every { mockPrompt.readLine(any()) } returns "prompted-name"

        initCommand = Init(
            templateExtractor = mockExtractor,
            prompt = mockPrompt
        )

        val result = initCommand.test(listOf("--template", "cli"))

        verify(exactly = 1) { mockPrompt.readLine(match { it.contains("Enter name") }) }
        verify { mockExtractor.extract("cli", match { it.fileName.toString() == "prompted-name" }) }
        Assertions.assertTrue(result.stdout.contains("Successfully generated"))
    }

    @Test
    fun `prompts for template when invalid template provided`() {
        every { mockPrompt.readLine(any()) } returns "1"

        initCommand = Init(
            templateExtractor = mockExtractor,
            prompt = mockPrompt
        )

        val result = initCommand.test(listOf("--template", "invalid-template", "--name", "valid_name"))

        verify { mockPrompt.readLine(match { it.contains("Enter choice") }) }
        verify { mockExtractor.extract("basic", any()) }
        Assertions.assertTrue(result.stdout.contains("Template 'invalid-template' does not exist."))
    }

    @Test
    fun `fails with invalid name option`() {
        initCommand = Init(
            templateExtractor = mockExtractor,
            prompt = mockPrompt
        )

        val exception = Assertions.assertThrows(BadParameterValue::class.java) {
            initCommand.parse(listOf("--name", "INVALID!"))
        }
        Assertions.assertTrue(exception.message!!.contains("Invalid name format"))
    }

}