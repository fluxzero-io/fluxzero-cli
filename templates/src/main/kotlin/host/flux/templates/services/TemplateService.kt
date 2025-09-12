package host.flux.templates.services

import host.flux.templates.models.TemplateInfo
import java.nio.file.Path

interface TemplateService {
    fun listTemplates(): List<TemplateInfo>
    fun extractTemplate(templateName: String, targetDir: Path)
    fun templateExists(templateName: String): Boolean
}