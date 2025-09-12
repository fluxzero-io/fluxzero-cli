package host.flux.templates.services

import host.flux.templates.models.TemplateInfo
import java.io.FileNotFoundException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipInputStream
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension

class FileSystemTemplateService(private val templatePath: Path) : TemplateService {

    init {
        if (!Files.exists(templatePath)) {
            throw FileNotFoundException("Template path does not exist: $templatePath")
        }
    }

    override fun listTemplates(): List<TemplateInfo> {
        return when {
            templatePath.isDirectory() -> {
                // List subdirectories as templates
                Files.list(templatePath).use { stream ->
                    stream
                        .filter { it.isDirectory() }
                        .map { TemplateInfo(name = it.name) }
                        .toList()
                }
            }
            templatePath.isRegularFile() && templatePath.name.endsWith(".zip") -> {
                // Single ZIP file template
                listOf(TemplateInfo(name = templatePath.nameWithoutExtension))
            }
            else -> {
                throw IllegalArgumentException("Template path must be either a directory or a ZIP file: $templatePath")
            }
        }
    }

    override fun extractTemplate(templateName: String, targetDir: Path) {
        when {
            templatePath.isDirectory() -> {
                // Extract from subdirectory
                val templateDir = templatePath.resolve(templateName)
                if (!Files.exists(templateDir) || !templateDir.isDirectory()) {
                    throw FileNotFoundException("Template '$templateName' not found in directory: $templatePath")
                }
                copyDirectory(templateDir, targetDir)
            }
            templatePath.isRegularFile() && templatePath.name.endsWith(".zip") -> {
                // Extract from ZIP file - template name should match the file name
                if (templateName != templatePath.nameWithoutExtension) {
                    throw FileNotFoundException("Template name '$templateName' does not match ZIP file: ${templatePath.nameWithoutExtension}")
                }
                extractZipFile(templatePath, targetDir)
            }
            else -> {
                throw IllegalArgumentException("Template path must be either a directory or a ZIP file: $templatePath")
            }
        }
    }

    override fun templateExists(templateName: String): Boolean {
        return listTemplates().any { it.name == templateName }
    }

    private fun copyDirectory(source: Path, target: Path) {
        Files.createDirectories(target)
        Files.walk(source).use { stream ->
            stream.forEach { sourcePath ->
                val relativePath = source.relativize(sourcePath)
                val targetPath = target.resolve(relativePath)
                
                if (Files.isDirectory(sourcePath)) {
                    Files.createDirectories(targetPath)
                } else {
                    Files.createDirectories(targetPath.parent)
                    Files.copy(sourcePath, targetPath)
                }
            }
        }
    }

    private fun extractZipFile(zipFile: Path, targetDir: Path) {
        Files.createDirectories(targetDir)
        Files.newInputStream(zipFile).use { fileStream ->
            extractZip(fileStream, targetDir)
        }
    }

    private fun extractZip(zipStream: InputStream, targetDir: Path) {
        ZipInputStream(zipStream).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val outPath = targetDir.resolve(entry.name).normalize()

                if (entry.isDirectory) {
                    Files.createDirectories(outPath)
                } else {
                    Files.createDirectories(outPath.parent)
                    Files.newOutputStream(outPath).use { out -> zip.copyTo(out) }
                }

                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }
}