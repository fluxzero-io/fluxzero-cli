package host.flux.templates.services

import host.flux.templates.models.ScaffoldProject
import host.flux.templates.models.ScaffoldResult
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
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
        every { mockTemplateService.extractTemplate(any(), any()) } answers {
            val target = secondArg<Path>()
            Files.createDirectories(target)
            Files.writeString(target.resolve("generated.txt"), "generated")
        }
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

    @Test
    fun `makes Unix build wrappers executable`() {
        every { mockTemplateService.extractTemplate(any(), any()) } answers {
            val outputDir = tempDir.resolve("my-project")
            Files.createDirectories(outputDir)
            Files.writeString(outputDir.resolve("mvnw"), "#!/bin/sh\n")
            Files.writeString(outputDir.resolve("mvnw.cmd"), "@REM Maven wrapper\n")
        }

        val request = ScaffoldProject(
            template = "test-template",
            name = "my-project",
            outputDir = tempDir.toString()
        )

        val result = scaffoldService.scaffoldProject(request)

        assertTrue(result.success)
        assertTrue(Files.isExecutable(tempDir.resolve("my-project/mvnw")))
    }

    @Test
    fun `generates directly into an empty in-place target`() {
        val request = ScaffoldProject(
            template = "test-template",
            name = "my-project",
            outputDir = tempDir.toString(),
            inPlace = true
        )

        val result = scaffoldService.scaffoldProject(request)

        assertTrue(result.success)
        assertEquals(tempDir.toAbsolutePath().toString(), result.outputPath)
        assertEquals("generated", Files.readString(tempDir.resolve("generated.txt")))
        verify {
            mockTemplateService.extractTemplate(
                "test-template",
                match { it != tempDir && it.parent == tempDir.parent && it.fileName.toString().startsWith(".fluxzero-scaffold-") }
            )
        }
    }

    @Test
    fun `in-place generation preserves managed dev state`() {
        val session = tempDir.resolve(".fluxzero/dev/session.json")
        Files.createDirectories(session.parent)
        Files.writeString(session, "{\"status\":\"starting\"}")
        val request = ScaffoldProject(
            template = "test-template",
            name = "my-project",
            outputDir = tempDir.toString(),
            inPlace = true
        )

        val result = scaffoldService.scaffoldProject(request)

        assertTrue(result.success)
        assertEquals("{\"status\":\"starting\"}", Files.readString(session))
        assertEquals("generated", Files.readString(tempDir.resolve("generated.txt")))
        verify { mockTemplateService.extractTemplate("test-template", match { it != tempDir }) }
    }

    @Test
    fun `in-place refactor failure leaves the target unchanged and permits a clean retry`() {
        val session = tempDir.resolve(".fluxzero/dev/session.json")
        Files.createDirectories(session.parent)
        Files.writeString(session, "managed-session")
        every { mockTemplateRefactor.refactorTemplate(any(), any()) } returns RefactorResult(
            success = false,
            message = "Refactor failed",
            error = "invalid template"
        )
        val request = ScaffoldProject(
            template = "test-template",
            name = "my-project",
            outputDir = tempDir.toString(),
            inPlace = true
        )

        val failed = scaffoldService.scaffoldProject(request)

        assertFalse(failed.success)
        assertEquals("managed-session", Files.readString(session))
        assertFalse(Files.exists(tempDir.resolve("generated.txt")))
        assertFalse(Files.list(tempDir.parent).use { entries ->
            entries.anyMatch { it.fileName.toString().startsWith(".fluxzero-scaffold-") }
        })

        every { mockTemplateRefactor.refactorTemplate(any(), any()) } returns RefactorResult(
            success = true,
            message = "Refactored successfully"
        )
        val retried = scaffoldService.scaffoldProject(request)

        assertTrue(retried.success)
        assertEquals("managed-session", Files.readString(session))
        assertEquals("generated", Files.readString(tempDir.resolve("generated.txt")))
    }

    @Test
    fun `in-place refactoring is isolated from managed dev json`() {
        val session = tempDir.resolve(".fluxzero/dev/session.json")
        Files.createDirectories(session.parent)
        Files.writeString(session, "original")
        val templateService = mockk<TemplateService>()
        every { templateService.templateExists("test-template") } returns true
        every { templateService.extractTemplate("test-template", any()) } answers {
            val staging = secondArg<Path>()
            Files.createDirectories(staging.resolve("config"))
            Files.writeString(staging.resolve("config/settings.json"), "original")
            Files.writeString(staging.resolve("refactor.yaml"), """
                operations:
                  - type: replace
                    files: ["**/*.json"]
                    find: "original"
                    replace: "updated"
                """.trimIndent())
        }
        val service = ScaffoldService(templateService, TemplateRefactor())

        val result = service.scaffoldProject(ScaffoldProject(
            template = "test-template",
            name = "my-project",
            outputDir = tempDir.toString(),
            inPlace = true
        ))

        assertTrue(result.success, result.message)
        assertEquals("original", Files.readString(session))
        assertEquals("updated", Files.readString(tempDir.resolve("config/settings.json")))
    }

    @Test
    fun `in-place generation rejects template-owned managed dev state`() {
        val session = tempDir.resolve(".fluxzero/dev/session.json")
        Files.createDirectories(session.parent)
        Files.writeString(session, "managed-session")
        every { mockTemplateService.extractTemplate(any(), any()) } answers {
            val staging = secondArg<Path>()
            Files.createDirectories(staging.resolve(".fluxzero/dev"))
            Files.writeString(staging.resolve(".fluxzero/dev/session.json"), "template-session")
        }

        val result = scaffoldService.scaffoldProject(ScaffoldProject(
            template = "test-template",
            name = "my-project",
            outputDir = tempDir.toString(),
            inPlace = true
        ))

        assertFalse(result.success)
        assertTrue(result.message.contains("must not contain managed .fluxzero/dev state"))
        assertEquals("managed-session", Files.readString(session))
    }

    @Test
    fun `in-place generation rejects case aliases for managed dev state on every platform`() {
        val session = tempDir.resolve(".fluxzero/dev/session.json")
        Files.createDirectories(session.parent)
        Files.writeString(session, "managed-session")
        every { mockTemplateService.extractTemplate(any(), any()) } answers {
            val staging = secondArg<Path>()
            Files.createDirectories(staging.resolve(".FLUXZERO/DEV"))
            Files.writeString(staging.resolve(".FLUXZERO/DEV/injected.json"), "template-state")
        }

        val result = scaffoldService.scaffoldProject(ScaffoldProject(
            template = "test-template",
            name = "my-project",
            outputDir = tempDir.toString(),
            inPlace = true
        ))

        assertFalse(result.success)
        assertTrue(result.message.contains("must not contain managed .fluxzero/dev state"))
        assertEquals("managed-session", Files.readString(session))
        assertFalse(Files.exists(tempDir.resolve(".fluxzero/dev/injected.json")))
    }

    @Test
    fun `in-place generation rechecks target contents immediately before publication`() {
        every { mockTemplateService.extractTemplate(any(), any()) } answers {
            val staging = secondArg<Path>()
            Files.createDirectories(staging)
            Files.writeString(staging.resolve("generated.txt"), "generated")
            Files.writeString(tempDir.resolve("external.txt"), "external")
        }

        val result = scaffoldService.scaffoldProject(ScaffoldProject(
            template = "test-template",
            name = "my-project",
            outputDir = tempDir.toString(),
            inPlace = true
        ))

        assertFalse(result.success)
        assertTrue(result.message.contains("files other than managed .fluxzero/dev state"))
        assertEquals("external", Files.readString(tempDir.resolve("external.txt")))
        assertFalse(Files.exists(tempDir.resolve("generated.txt")))
    }

    @Test
    fun `concurrent in-place initialization is rejected while the first scaffold holds the target lock`() {
        val extractionStarted = CountDownLatch(1)
        val releaseExtraction = CountDownLatch(1)
        val blockingTemplateService = object : TemplateService {
            override fun listTemplates(): List<TemplateInfo> = emptyList()
            override fun templateExists(templateName: String): Boolean = true
            override fun extractTemplate(templateName: String, targetDir: Path) {
                Files.createDirectories(targetDir)
                extractionStarted.countDown()
                check(releaseExtraction.await(10, TimeUnit.SECONDS)) { "Timed out waiting to release extraction" }
                Files.writeString(targetDir.resolve("generated.txt"), "generated")
            }
        }
        val service = ScaffoldService(blockingTemplateService, mockTemplateRefactor)
        val request = ScaffoldProject(
            template = "test-template",
            name = "my-project",
            outputDir = tempDir.toString(),
            inPlace = true
        )
        val executor = Executors.newSingleThreadExecutor()

        try {
            val first = executor.submit<ScaffoldResult> { service.scaffoldProject(request) }
            assertTrue(extractionStarted.await(10, TimeUnit.SECONDS))

            val second = service.scaffoldProject(request)

            assertFalse(second.success)
            assertTrue(second.message.contains("Another in-place scaffold is already in progress"))
            releaseExtraction.countDown()
            assertTrue(first.get(10, TimeUnit.SECONDS).success)
            assertEquals("generated", Files.readString(tempDir.resolve("generated.txt")))
            assertTrue(Files.isRegularFile(tempDir.parent.resolve(".${tempDir.fileName}.fluxzero-scaffold.lock")))
        } finally {
            releaseExtraction.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `in-place generation rejects unrelated target content`() {
        Files.writeString(tempDir.resolve("notes.txt"), "keep me")
        val request = ScaffoldProject(
            template = "test-template",
            name = "my-project",
            outputDir = tempDir.toString(),
            inPlace = true
        )

        val result = scaffoldService.scaffoldProject(request)

        assertFalse(result.success)
        assertTrue(result.message.contains("files other than managed .fluxzero/dev state"))
        verify(exactly = 0) { mockTemplateService.extractTemplate(any(), any()) }
        assertEquals("keep me", Files.readString(tempDir.resolve("notes.txt")))
    }

    @Test
    fun `in-place generation rejects non-runtime Fluxzero configuration`() {
        Files.createDirectories(tempDir.resolve(".fluxzero"))
        Files.writeString(tempDir.resolve(".fluxzero/dev.yaml"), "apps: []")
        val request = ScaffoldProject(
            template = "test-template",
            name = "my-project",
            outputDir = tempDir.toString(),
            inPlace = true
        )

        val result = scaffoldService.scaffoldProject(request)

        assertFalse(result.success)
        verify(exactly = 0) { mockTemplateService.extractTemplate(any(), any()) }
    }

    @Test
    fun `temporary scaffolding always retains the named child layout`() {
        val result = scaffoldService.scaffoldProjectToTempDir(ScaffoldProject(
            template = "test-template",
            name = "my-project",
            inPlace = true
        ))

        try {
            assertTrue(result.success)
            val output = Path.of(result.outputPath!!)
            assertEquals("my-project", output.fileName.toString())
            assertEquals("generated", Files.readString(output.resolve("generated.txt")))
            verify { mockTemplateService.extractTemplate("test-template", match { it.fileName.toString() == "my-project" }) }
        } finally {
            result.outputPath?.let { Path.of(it).parent.toFile().deleteRecursively() }
        }
    }
}
