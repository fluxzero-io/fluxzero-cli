package host.flux.templates.services

import host.flux.templates.models.InitRequest
import host.flux.templates.models.InitResult
import host.flux.templates.refactor.TemplateRefactor
import host.flux.templates.refactor.TemplateVariables
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.absolute

class InitializationService(
    private val templateService: TemplateService = TemplateService(),
    private val templateRefactor: TemplateRefactor = TemplateRefactor()
) {
    
    fun initializeProject(request: InitRequest): InitResult {
        return try {
            // Validate template exists
            if (!templateService.templateExists(request.template)) {
                return InitResult(
                    success = false,
                    message = "Template '${request.template}' does not exist",
                    error = "Template not found"
                )
            }
            
            // Validate project name
            val nameRegex = Regex("^[0-9a-z-_]{1,50}$")
            if (!nameRegex.matches(request.name)) {
                return InitResult(
                    success = false,
                    message = "Invalid name format: must be 1-50 chars of digits, lowercase letters, '-' or '_' only",
                    error = "Invalid name format"
                )
            }
            
            // Determine output directory
            val baseDir = request.outputDir?.let { Paths.get(it) } ?: Paths.get("")
            val outputDir = baseDir.resolve(request.name)
            
            // Check if directory already exists
            if (Files.exists(outputDir) && Files.list(outputDir).use { it.findFirst().isPresent }) {
                return InitResult(
                    success = false,
                    message = "Directory '${outputDir.absolute()}' already exists and is not empty",
                    error = "Directory exists"
                )
            }
            
            // Extract template
            templateService.extractTemplate(request.template, outputDir)
            
            // Apply template refactoring
            val variables = TemplateVariables(
                packageName = request.packageName,
                projectName = request.name,
                groupId = request.groupId,
                buildSystem = request.buildSystem
            )
            val refactorResult = templateRefactor.refactorTemplate(
                templateRoot = outputDir,
                variables = variables
            )
            
            // If refactoring failed, return the error
            if (!refactorResult.success) {
                return InitResult(
                    success = false,
                    message = refactorResult.message,
                    error = refactorResult.error
                )
            }
            
            // Initialize Git if requested
            if (request.initGit) {
                initializeGit(outputDir)
            }
            
            val finalMessage = buildString {
                append("Successfully generated your project at '${outputDir.absolute()}'")
                if (refactorResult.warnings.isNotEmpty()) {
                    append("\n\nWarnings during template refactoring:")
                    refactorResult.warnings.forEach { warning ->
                        append("\n- $warning")
                    }
                }
            }
            
            InitResult(
                success = true,
                message = finalMessage,
                outputPath = outputDir.absolute().toString()
            )
            
        } catch (e: Exception) {
            InitResult(
                success = false,
                message = "Failed to initialize project: ${e.message}",
                error = e.message
            )
        }
    }
    
    private fun initializeGit(outputDir: Path) {
        try {
            ProcessBuilder("git", "init")
                .directory(outputDir.toFile())
                .start()
                .waitFor()
        } catch (e: Exception) {
            // Git initialization is optional - don't fail the whole operation
            System.err.println("Failed to initialize Git: ${e.message}")
        }
    }
    
    fun listAvailableTemplates() = templateService.listTemplates()
}