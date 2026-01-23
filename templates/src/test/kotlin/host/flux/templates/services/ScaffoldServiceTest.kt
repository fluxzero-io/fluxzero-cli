package host.flux.templates.services

import host.flux.templates.models.ScaffoldProject
import host.flux.templates.models.TemplateInfo
import host.flux.templates.refactor.TemplateRefactor
import host.flux.templates.refactor.RefactorResult
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class ScaffoldServiceTest {

    private lateinit var mockTemplateService: TemplateService
    private lateinit var mockTemplateRefactor: TemplateRefactor
    private lateinit var scaffoldService: ScaffoldService

    @TempDir
    lateinit var tempDir: Path

    @BeforeEach
    fun setup() {
        mockTemplateService = mockk()
        mockTemplateRefactor = mockk()
        scaffoldService = ScaffoldService(mockTemplateService, mockTemplateRefactor)

        every { mockTemplateService.templateExists(any()) } returns true
        every { mockTemplateService.extractTemplate(any(), any()) } returns Unit
        every { mockTemplateRefactor.refactorTemplate(any(), any()) } returns RefactorResult(
            success = true,
            message = "Refactored successfully"
        )
    }

    @Test
    fun `normalizes uppercase name to lowercase`() {
        val request = ScaffoldProject(
            template = "test-template",
            name = "MyUpperCaseName",
            outputDir = tempDir.toString()
        )

        val result = scaffoldService.scaffoldProject(request)

        assertTrue(result.success)
        verify { mockTemplateService.extractTemplate("test-template", match { it.endsWith("myuppercasename") }) }
    }

    @Test
    fun `replaces spaces with hyphens`() {
        val request = ScaffoldProject(
            template = "test-template",
            name = "My Project Name",
            outputDir = tempDir.toString()
        )

        val result = scaffoldService.scaffoldProject(request)

        assertTrue(result.success)
        verify { mockTemplateService.extractTemplate("test-template", match { it.endsWith("my-project-name") }) }
    }

    @Test
    fun `removes special characters`() {
        val request = ScaffoldProject(
            template = "test-template",
            name = "My@Project#Name!",
            outputDir = tempDir.toString()
        )

        val result = scaffoldService.scaffoldProject(request)

        assertTrue(result.success)
        verify { mockTemplateService.extractTemplate("test-template", match { it.endsWith("myprojectname") }) }
    }

    @Test
    fun `normalizes consecutive separators`() {
        val request = ScaffoldProject(
            template = "test-template",
            name = "my---project___name",
            outputDir = tempDir.toString()
        )

        val result = scaffoldService.scaffoldProject(request)

        assertTrue(result.success)
        verify { mockTemplateService.extractTemplate("test-template", match { it.endsWith("my-project-name") }) }
    }

    @Test
    fun `trims leading and trailing separators`() {
        val request = ScaffoldProject(
            template = "test-template",
            name = "-_-my-project-_-",
            outputDir = tempDir.toString()
        )

        val result = scaffoldService.scaffoldProject(request)

        assertTrue(result.success)
        verify { mockTemplateService.extractTemplate("test-template", match { it.endsWith("my-project") }) }
    }

    @Test
    fun `handles mixed special characters and spaces`() {
        val request = ScaffoldProject(
            template = "test-template",
            name = "My Super@Cool Project #123!",
            outputDir = tempDir.toString()
        )

        val result = scaffoldService.scaffoldProject(request)

        assertTrue(result.success)
        verify { mockTemplateService.extractTemplate("test-template", match { it.endsWith("my-supercool-project-123") }) }
    }

    @Test
    fun `truncates name to 50 characters`() {
        val request = ScaffoldProject(
            template = "test-template",
            name = "a".repeat(100),
            outputDir = tempDir.toString()
        )

        val result = scaffoldService.scaffoldProject(request)

        assertTrue(result.success)
        verify {
            mockTemplateService.extractTemplate("test-template", match {
                val dirName = it.fileName.toString()
                dirName.length == 50
            })
        }
    }

    @Test
    fun `fails when name becomes empty after normalization`() {
        val request = ScaffoldProject(
            template = "test-template",
            name = "@#$%^&*()",
            outputDir = tempDir.toString()
        )

        val result = scaffoldService.scaffoldProject(request)

        assertFalse(result.success)
        assertTrue(result.message!!.contains("Invalid project name"))
        assertTrue(result.message!!.contains("must contain at least one letter or number"))
    }

    @Test
    fun `handles already valid lowercase name`() {
        val request = ScaffoldProject(
            template = "test-template",
            name = "my-valid-name",
            outputDir = tempDir.toString()
        )

        val result = scaffoldService.scaffoldProject(request)

        assertTrue(result.success)
        verify { mockTemplateService.extractTemplate("test-template", match { it.endsWith("my-valid-name") }) }
    }

    @Test
    fun `preserves numbers in name`() {
        val request = ScaffoldProject(
            template = "test-template",
            name = "MyProject123",
            outputDir = tempDir.toString()
        )

        val result = scaffoldService.scaffoldProject(request)

        assertTrue(result.success)
        verify { mockTemplateService.extractTemplate("test-template", match { it.endsWith("myproject123") }) }
    }

    @Test
    fun `handles underscores correctly`() {
        val request = ScaffoldProject(
            template = "test-template",
            name = "my_project_name",
            outputDir = tempDir.toString()
        )

        val result = scaffoldService.scaffoldProject(request)

        assertTrue(result.success)
        verify { mockTemplateService.extractTemplate("test-template", match { it.endsWith("my_project_name") }) }
    }
}