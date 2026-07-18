package host.flux.cli.commands.templates

import com.github.ajalt.clikt.testing.test
import host.flux.templates.services.TemplateService
import host.flux.templates.services.ClasspathTemplateService
import host.flux.templates.models.TemplateInfo
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ListTest {

    @Test
    fun `should list all available templates`() {
        val mockService = mockk<TemplateService>()
        every { mockService.listTemplates() } returns listOf(
            TemplateInfo("flux-basic-kotlin", "Kotlin starter"),
            TemplateInfo("flux-basic-java", "Java starter")
        )

        val command = List(mockService)
        val result = command.test()

        assertEquals(0, result.statusCode)
        assertEquals(
            """
            flux-basic-kotlin: Kotlin starter
            flux-basic-java: Java starter
            """.trimIndent(),
            result.output.trim()
        )
    }

    @Test
    fun `should handle empty template list`() {
        val mockService = mockk<TemplateService>()
        every { mockService.listTemplates() } returns emptyList()

        val command = List(mockService)
        val result = command.test()

        assertEquals(0, result.statusCode)
        assertEquals("", result.output.trim())
    }

}
