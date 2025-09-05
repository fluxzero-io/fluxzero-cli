package host.flux.cli.template

import org.junit.jupiter.api.Assertions.assertTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test

class TemplateExtractorTest {

    @Test
    fun `extract should unzip template into target directory`() {
        val extractor = TemplateExtractor()
        val tempDir: Path = Files.createTempDirectory("template-test")

        extractor.extract("flux-kotlin-single", tempDir)

        // Adjust based on your ZIP content
        val expectedFile: Path = tempDir.resolve("README.md")
        assertTrue(Files.exists(expectedFile), "Expected file 'README.md' to exist after extraction")

        // Cleanup
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun `listTemplates should return template names from index file`() {
        val extractor = TemplateExtractor()

        val templates = extractor.listTemplates()

        assertTrue(templates.isNotEmpty(), "Template list should not be empty")
        assertTrue("flux-kotlin-single" in templates, "Expected 'flux-kotlin-single' to be listed")
    }
}