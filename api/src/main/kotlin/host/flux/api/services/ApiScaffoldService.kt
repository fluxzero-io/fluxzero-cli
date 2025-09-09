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
     * InputStream wrapper that deletes a directory when the stream is closed
     */
    private class CleanupInputStream(
        private val delegate: InputStream,
        private val tempDir: Path
    ) : FilterInputStream(delegate) {
        
        override fun close() {
            try {
                super.close()
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }
    }
    
    /**
     * Result of API scaffolding operation
     */
    data class ApiScaffoldResult(
        val success: Boolean,
        val inputStream: InputStream? = null,
        val error: String? = null
    )

    /**
     * Scaffolds a project and returns it as a streaming zip file
     */
    fun scaffoldProjectAsZip(request: InitRequest): ApiScaffoldResult {
        var tempDir: Path? = null
        
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

            // Create streaming zip file from the scaffolded project
            val projectPath = tempDir.resolve(request.name)
            val rawInputStream = ZipUtils.zipDirectoryStreaming(projectPath, request.name)
            
            // Wrap the stream to auto-cleanup temp directory when closed
            val cleanupInputStream = CleanupInputStream(rawInputStream, tempDir)
            
            return ApiScaffoldResult(
                success = true,
                inputStream = cleanupInputStream
            )
            
        } catch (e: Exception) {
            // Clean up on error - delete entire temp directory
            tempDir?.toFile()?.deleteRecursively()
            return ApiScaffoldResult(
                success = false,
                error = e.message ?: "Unknown error occurred"
            )
        }
    }
}