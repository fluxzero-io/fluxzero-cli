package host.flux.api.services

import host.flux.api.models.InitRequest
import host.flux.api.utils.ZipUtils
import host.flux.templates.models.BuildSystem
import host.flux.templates.models.ScaffoldProject
import host.flux.templates.services.ScaffoldService
import java.io.FilterInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * Service for handling API-specific scaffolding operations that return zip files
 */
class ApiScaffoldService(
    private val scaffoldService: ScaffoldService = ScaffoldService()
) {
    
    
    /**
     * Result of API scaffolding operation
     */
    data class ApiScaffoldResult(
        val success: Boolean,
        val zipFile: Path? = null,
        val size: Long? = null,
        val error: String? = null
    )

    /**
     * Scaffolds a project and returns it as a zip file
     */
    fun scaffoldProjectAsZip(request: InitRequest): ApiScaffoldResult {
        var tempDir: Path? = null
        var zipFile: Path? = null
        
        try {
            // Create temporary directory
            tempDir = Files.createTempDirectory("scaffold")

            // Parse build system
            val buildSystem = when (request.buildSystem?.lowercase()) {
                "maven" -> BuildSystem.MAVEN
                "gradle" -> BuildSystem.GRADLE
                else -> null
            }

            val scaffoldRequest = ScaffoldProject(
                template = request.template,
                name = request.name,
                outputDir = tempDir.toString(),
                packageName = request.packageName,
                groupId = request.groupId,
                artifactId = request.artifactId,
                description = request.description,
                buildSystem = buildSystem,
                initGit = request.initGit
            )

            // Scaffold project
            val result = scaffoldService.scaffoldProject(scaffoldRequest)

            if (!result.success) {
                // Clean up on error - delete entire temp directory
                tempDir.toFile().deleteRecursively()
                return ApiScaffoldResult(
                    success = false,
                    error = result.error ?: "Unknown error occurred during scaffolding"
                )
            }

            // Create zip file from the scaffolded project
            val projectPath = tempDir.resolve(request.name)
            zipFile = ZipUtils.zipDirectoryToFile(projectPath, request.name)
            val zipSize = Files.size(zipFile)
            
            // Clean up scaffolded project directory (but keep the zip file)
            tempDir.toFile().deleteRecursively()
            
            return ApiScaffoldResult(
                success = true,
                zipFile = zipFile,
                size = zipSize
            )
            
        } catch (e: Exception) {
            // Clean up on error - delete temp directory and zip file
            tempDir?.toFile()?.deleteRecursively()
            zipFile?.let { Files.deleteIfExists(it) }
            return ApiScaffoldResult(
                success = false,
                error = e.message ?: "Unknown error occurred"
            )
        }
    }
}