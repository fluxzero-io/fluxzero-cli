package host.flux.templates.services

import host.flux.templates.models.TemplateInfo
import java.io.FileNotFoundException
import java.io.InputStream
import java.nio.file.Path

class ClasspathTemplateService(private val resourceRoot: String = "/templates") : TemplateService {

    override fun listTemplates(): List<TemplateInfo> {
        val indexPath = "$resourceRoot/templates.csv"
        val indexStream = this::class.java.getResourceAsStream(indexPath)
            ?: throw FileNotFoundException("Could not find template index at $indexPath")

        return indexStream.bufferedReader().readLines()
            .filter { it.isNotBlank() }
            .map { it.trim() }
            .map { TemplateInfo(name = it) }
    }

    override fun extractTemplate(templateName: String, targetDir: Path) {
        val zipStream: InputStream = openZipStream(templateName)
        ZipTemplateExtractor.extract(zipStream, targetDir)
    }

    private fun openZipStream(templateName: String): InputStream {
        val resourcePath = "$resourceRoot/$templateName.zip"
        val zipStream: InputStream = this::class.java.getResourceAsStream(resourcePath)
            ?: throw FileNotFoundException("Template '$templateName' not found in classpath at $resourcePath")

        return zipStream
    }

    override fun templateExists(templateName: String): Boolean {
        return listTemplates().any { it.name == templateName }
    }
}
