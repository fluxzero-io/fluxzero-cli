package host.flux.templates.services

import host.flux.templates.models.ScaffoldProject
import host.flux.templates.models.ScaffoldResult
import host.flux.templates.refactor.TemplateRefactor
import host.flux.templates.refactor.TemplateVariables
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.absolute

class ScaffoldService(
    private val templateService: TemplateService = ClasspathTemplateService(),
    private val templateRefactor: TemplateRefactor = TemplateRefactor()
) {
    
    fun scaffoldProject(request: ScaffoldProject): ScaffoldResult {
        return try {
            // Validate template exists
            if (!templateService.templateExists(request.template)) {
                return ScaffoldResult(
                    success = false,
                    message = "Template '${request.template}' does not exist",
                    error = "Template not found"
                )
            }

            // Normalize project name
            val normalizedName = normalizeName(request.name)
            if (normalizedName.isEmpty()) {
                return ScaffoldResult(
                    success = false,
                    message = "Invalid project name '${request.name}'. Name must contain at least one letter or number. Special characters and spaces are allowed and will be normalized.",
                    error = "Invalid name format"
                )
            }
            
            // Determine output directory
            val baseDir = request.outputDir?.let { Paths.get(it) } ?: Paths.get("")
            val outputDir = baseDir.resolve(normalizedName)
            
            // Check if directory already exists
            if (Files.exists(outputDir) && Files.list(outputDir).use { it.findFirst().isPresent }) {
                return ScaffoldResult(
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
                projectName = normalizedName,
                groupId = request.groupId,
                artifactId = request.artifactId,
                description = request.description,
                buildSystem = request.buildSystem
            )
            val refactorResult = templateRefactor.refactorTemplate(
                templateRoot = outputDir,
                variables = variables
            )
            
            // If refactoring failed, return the error
            if (!refactorResult.success) {
                return ScaffoldResult(
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
            
            ScaffoldResult(
                success = true,
                message = finalMessage,
                outputPath = outputDir.absolute().toString()
            )
            
        } catch (e: Exception) {
            ScaffoldResult(
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
    
    /**
     * Scaffolds a project into a temporary directory and returns the path.
     * This is useful for API endpoints that need to zip the project.
     */
    fun scaffoldProjectToTempDir(request: ScaffoldProject): ScaffoldResult {
        val tempDir = Files.createTempDirectory("flux-scaffold-")
        
        val modifiedRequest = request.copy(
            outputDir = tempDir.toString()
        )
        
        return scaffoldProject(modifiedRequest)
    }
    
    fun listAvailableTemplates() = templateService.listTemplates()

    /**
     * Normalizes a project name to ensure it follows valid naming conventions.
     *
     * Rules:
     * - Converts to lowercase
     * - Replaces spaces with hyphens
     * - Removes special characters (keeps only alphanumeric, hyphens, underscores)
     * - Collapses consecutive hyphens/underscores into single separator
     * - Trims leading/trailing separators
     * - Truncates to 50 characters maximum
     */
    private fun normalizeName(name: String): String {
        return name
            .lowercase()                                    // Convert to lowercase
            .replace(' ', '-')                              // Replace spaces with hyphens
            .replace(Regex("[^a-z0-9_-]"), "")             // Remove special characters
            .replace(Regex("[-_]{2,}"), "-")               // Collapse consecutive separators
            .trim('-', '_')                                 // Trim leading/trailing separators
            .take(50)                                       // Truncate to max 50 chars
            .trimEnd('-', '_')                             // Trim again in case truncation created trailing separator
    }
}