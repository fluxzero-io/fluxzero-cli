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
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test

class InitTest {

    @TempDir
    lateinit var tempDir: Path

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
    fun `passes explicit in-place generation to the scaffold service`() {
        initCommand = Init(
            scaffoldService = mockInitService,
            prompt = mockPrompt
        )

        val result = initCommand.test(
            listOf(
                "--template", "webapp",
                "--name", "valid_name",
                "--package", "com.test.myapp",
                "--build", "maven",
                "--dir", Paths.get("").toAbsolutePath().toString(),
                "--in-place"
            )
        )

        verify(exactly = 1) {
            mockInitService.scaffoldProject(match { it.inPlace })
        }
        Assertions.assertTrue(result.stdout.contains("Project initialized successfully"))
    }

    @Test
    fun `help distinguishes parent and in-place targets`() {
        val result = Init(
            scaffoldService = mockInitService,
            prompt = mockPrompt
        ).test("--help")

        Assertions.assertEquals(0, result.statusCode)
        Assertions.assertTrue(result.stdout.contains("--in-place"))
        Assertions.assertTrue(result.stdout.contains("Parent directory for the named project"))
        Assertions.assertTrue(result.stdout.contains("Generate directly in --dir"))
    }

    @Test
    fun `named child generation remains the default`() {
        initCommand = Init(
            scaffoldService = mockInitService,
            prompt = mockPrompt
        )

        initCommand.test(
            listOf(
                "--template", "webapp",
                "--name", "valid_name",
                "--package", "com.test.myapp",
                "--build", "maven"
            )
        )

        verify(exactly = 1) {
            mockInitService.scaffoldProject(match { !it.inPlace })
        }
    }

    @Test
    fun `real template generates in place alongside managed dev state`() {
        val session = tempDir.resolve(".fluxzero/dev/session.json")
        Files.createDirectories(session.parent)
        Files.writeString(session, "{\"status\":\"starting\"}")

        val result = Init().test(
            listOf(
                "--template", "flux-basic-java",
                "--name", "Example App",
                "--package", "com.example.app",
                "--group-id", "com.example",
                "--artifact-id", "example-app",
                "--description", "Test application",
                "--build", "maven",
                "--dir", tempDir.toString(),
                "--in-place"
            )
        )

        Assertions.assertEquals(0, result.statusCode, result.stderr)
        Assertions.assertTrue(Files.isRegularFile(tempDir.resolve("pom.xml")))
        Assertions.assertTrue(Files.isExecutable(tempDir.resolve("mvnw")))
        Assertions.assertTrue(Files.isRegularFile(tempDir.resolve(".fluxzero/dev.yaml")))
        Assertions.assertEquals("{\"status\":\"starting\"}", Files.readString(session))
        Assertions.assertTrue(result.stdout.contains(tempDir.toAbsolutePath().toString()))
    }

    @Test
    fun `prompts for name when not provided`() {
        every { mockPrompt.readLine(match { it.contains("project name") }) } returns "prompted-name"
        every { mockPrompt.readLine(match { it.contains("Enter package") }) } returns "com.test.app"
        every { mockPrompt.select(any(), any(), any()) } returns 0

        initCommand = Init(
            scaffoldService = mockInitService,
            prompt = mockPrompt
        )

        val result = initCommand.test(listOf("--template", "cli"))

        verify(exactly = 1) { mockPrompt.readLine(match { it.contains("project name") }) }
        verify(exactly = 1) { mockPrompt.readLine(match { it.contains("Enter package") }) }
        verify(exactly = 1) { mockPrompt.select(match { it.contains("build system") }, any(), any()) }
        verify { mockInitService.scaffoldProject(any()) }
        Assertions.assertTrue(result.stdout.contains("Project initialized successfully"))
    }

    @Test
    fun `prompts for template when invalid template provided`() {
        every { mockPrompt.select(any(), any(), any()) } returns 0 andThen 1
        every { mockPrompt.readLine(match { it.contains("Enter package") }) } returns "com.test.app"

        initCommand = Init(
            scaffoldService = mockInitService,
            prompt = mockPrompt
        )

        val result = initCommand.test(listOf("--template", "invalid-template", "--name", "valid_name"))

        verify(exactly = 2) { mockPrompt.select(any(), any(), any()) }
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
