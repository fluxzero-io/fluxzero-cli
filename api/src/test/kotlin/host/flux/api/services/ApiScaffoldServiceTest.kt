package host.flux.api.services

import host.flux.api.models.InitRequest
import host.flux.templates.models.ScaffoldResult
import host.flux.templates.services.ScaffoldService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.slot
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class ApiScaffoldServiceTest {

    private lateinit var mockScaffoldService: ScaffoldService
    private lateinit var apiScaffoldService: ApiScaffoldService

    @TempDir
    lateinit var tempDir: Path

    @BeforeEach
    fun setup() {
        mockScaffoldService = mockk()
        apiScaffoldService = ApiScaffoldService(mockScaffoldService)
    }

    @Test
    fun `uses normalized name from scaffold result outputPath`() {
        // Given: User provides uppercase name
        val request = InitRequest(
            template = "test-template",
            name = "MyProject",
            packageName = "com.test"
        )

        // Capture the scaffold request to get the temp directory it creates
        val scaffoldRequestSlot = slot<host.flux.templates.models.ScaffoldProject>()

        every { mockScaffoldService.scaffoldProject(capture(scaffoldRequestSlot)) } answers {
            // Create the project directory with normalized name in the outputDir
            val outputDir = Path.of(scaffoldRequestSlot.captured.outputDir!!)
            val projectDir = outputDir.resolve("myproject")
            Files.createDirectories(projectDir)
            Files.createFile(projectDir.resolve("test.txt"))

            ScaffoldResult(
                success = true,
                message = "Success",
                outputPath = projectDir.toString()
            )
        }

        // When: Scaffold project as zip
        val result = apiScaffoldService.scaffoldProjectAsZip(request)

        // Then: Should succeed and use normalized path
        assertTrue(result.success, "Expected success but got error: ${result.error}")
        assertNotNull(result.zipFile)
        assertNull(result.error)

        // Verify the zip file was created
        assertTrue(Files.exists(result.zipFile!!))
        assertTrue(Files.size(result.zipFile!!) > 0)
    }

    @Test
    fun `handles special characters in project name`() {
        // Given: User provides name with special characters
        val request = InitRequest(
            template = "test-template",
            name = "My@Project#123",
            packageName = "com.test"
        )

        // Capture the scaffold request
        val scaffoldRequestSlot = slot<host.flux.templates.models.ScaffoldProject>()

        every { mockScaffoldService.scaffoldProject(capture(scaffoldRequestSlot)) } answers {
            // Create the project directory with normalized name
            val outputDir = Path.of(scaffoldRequestSlot.captured.outputDir!!)
            val projectDir = outputDir.resolve("myproject123")
            Files.createDirectories(projectDir)
            Files.createFile(projectDir.resolve("test.txt"))

            ScaffoldResult(
                success = true,
                message = "Success",
                outputPath = projectDir.toString()
            )
        }

        // When: Scaffold project as zip
        val result = apiScaffoldService.scaffoldProjectAsZip(request)

        // Then: Should succeed
        assertTrue(result.success, "Expected success but got error: ${result.error}")
        assertNotNull(result.zipFile)
        assertTrue(Files.exists(result.zipFile!!))
    }

    @Test
    fun `returns error when scaffold service fails`() {
        // Given: Request that will fail
        val request = InitRequest(
            template = "test-template",
            name = "@#$%",
            packageName = "com.test"
        )

        every { mockScaffoldService.scaffoldProject(any()) } returns ScaffoldResult(
            success = false,
            message = "Invalid project name",
            error = "Invalid name format"
        )

        // When: Scaffold project as zip
        val result = apiScaffoldService.scaffoldProjectAsZip(request)

        // Then: Should return error
        assertFalse(result.success)
        assertNotNull(result.error)
        assertEquals("Invalid name format", result.error)
        assertNull(result.zipFile)
    }

    @Test
    fun `passes original name to scaffold service for normalization`() {
        // Given: Request with uppercase name
        val request = InitRequest(
            template = "test-template",
            name = "UpperCaseName",
            packageName = "com.test"
        )

        val scaffoldRequestSlot = slot<host.flux.templates.models.ScaffoldProject>()

        every { mockScaffoldService.scaffoldProject(capture(scaffoldRequestSlot)) } answers {
            val outputDir = Path.of(scaffoldRequestSlot.captured.outputDir!!)
            val projectDir = outputDir.resolve("uppercasename")
            Files.createDirectories(projectDir)
            Files.createFile(projectDir.resolve("dummy.txt"))

            ScaffoldResult(
                success = true,
                message = "Success",
                outputPath = projectDir.toString()
            )
        }

        // When: Scaffold project as zip
        apiScaffoldService.scaffoldProjectAsZip(request)

        // Then: Verify original name was passed to scaffold service (it will normalize it)
        verify {
            mockScaffoldService.scaffoldProject(
                match { it.name == "UpperCaseName" }
            )
        }
    }
}