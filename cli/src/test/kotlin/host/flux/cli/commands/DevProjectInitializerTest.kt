package host.flux.cli.commands

import host.flux.cli.prompt.Prompt
import host.flux.templates.models.ScaffoldResult
import host.flux.templates.models.TemplateInfo
import host.flux.templates.services.ScaffoldService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DevProjectInitializerTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `offers the three explicit choices and supports cancellation`() {
        val output = mutableListOf<String>()
        val prompt = QueuePrompt("3")
        val initializer = InteractiveDevProjectInitializer(
            prompt = prompt,
            scaffoldService = mockk(relaxed = true),
            output = output::add
        )

        assertNull(initializer.initialize(directory))
        assertEquals(
            listOf(
                "Create a new project in the current folder",
                "Create a new project in a subfolder",
                "Cancel"
            ),
            prompt.selections.single().second
        )
    }

    @Test
    fun `creates selected template directly in current empty folder`() {
        val service = mockk<ScaffoldService>()
        every { service.listAvailableTemplates() } returns listOf(TemplateInfo("basic", "Basic"))
        every { service.scaffoldProject(any()) } returns ScaffoldResult(
            true, "Created", directory.toAbsolutePath().toString()
        )
        val initializer = InteractiveDevProjectInitializer(
            prompt = QueuePrompt("1", "1", "example", "com.example.app", "1"),
            scaffoldService = service,
            output = { }
        )

        assertEquals(directory.toAbsolutePath(), initializer.initialize(directory))
        verify {
            service.scaffoldProject(match {
                it.inPlace && it.outputDir == directory.toString() && it.name == "example"
            })
        }
    }

    @Test
    fun `creates selected project in a subfolder`() {
        val generated = directory.resolve("example").toAbsolutePath()
        val service = mockk<ScaffoldService>()
        every { service.listAvailableTemplates() } returns listOf(TemplateInfo("basic", "Basic"))
        every { service.scaffoldProject(any()) } returns ScaffoldResult(true, "Created", generated.toString())
        val initializer = InteractiveDevProjectInitializer(
            prompt = QueuePrompt("2", "1", "example", "com.example.app", "2"),
            scaffoldService = service,
            output = { }
        )

        assertEquals(generated, initializer.initialize(directory))
        verify { service.scaffoldProject(match { !it.inPlace && it.name == "example" }) }
    }

    private class QueuePrompt(vararg answers: String) : Prompt {
        private val answers = ArrayDeque(answers.toList())
        val selections = mutableListOf<Pair<String, List<String>>>()

        override fun readLine(prompt: String): String = answers.removeFirst()

        override fun select(question: String, options: List<String>, defaultIndex: Int): Int {
            selections += question to options
            return answers.removeFirst().toInt() - 1
        }
    }
}
