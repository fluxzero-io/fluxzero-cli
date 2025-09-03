package host.flux.cli.commands.templates

import com.github.ajalt.clikt.testing.test
import host.flux.cli.template.TemplateExtractor
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ListTest {

    @Test
    fun `should list all available templates`() {
        val mockExtractor = mockk<TemplateExtractor>()
        every { mockExtractor.listTemplates() } returns listOf("flux-kotlin-single", "flux-kotlin-multi", "flux-java-spring")

        val command = List(mockExtractor)
        val result = command.test()

        assertEquals(0, result.statusCode)
        assertEquals(
            """
            flux-kotlin-single
            flux-kotlin-multi
            flux-java-spring
            """.trimIndent(),
            result.output.trim()
        )
    }

    @Test
    fun `should handle empty template list`() {
        val mockExtractor = mockk<TemplateExtractor>()
        every { mockExtractor.listTemplates() } returns emptyList()

        val command = List(mockExtractor)
        val result = command.test()

        assertEquals(0, result.statusCode)
        assertEquals("", result.output.trim())
    }

}