package host.flux.templates.refactor

import host.flux.templates.models.BuildSystem

data class TemplateVariables(
    val packageName: String,
    val projectName: String,
    val groupId: String? = null,
    val artifactId: String? = null,
    val description: String? = null,
    val buildSystem: BuildSystem? = null
) {
    /**
     * Get the final group ID, using packageName as fallback if groupId is null
     */
    val finalGroupId: String get() = groupId ?: packageName
    
    /**
     * Get the final artifact ID, using projectName as fallback if artifactId is null
     */
    val finalArtifactId: String get() = artifactId ?: projectName
    
    /**
     * Get the final description, using a default if description is null
     */
    val finalDescription: String get() = description ?: "A Flux application"
    
    /**
     * Get the package path with slashes (for directory structures)
     */
    val packagePath: String get() = packageName.replace(".", "/")
    
    
    companion object {
        /**
         * Create TemplateVariables with sensible defaults
         */
        fun create(
            packageName: String = "com.example.app",
            projectName: String,
            groupId: String? = null,
            artifactId: String? = null,
            description: String? = null,
            buildSystem: BuildSystem? = null
        ): TemplateVariables {
            return TemplateVariables(
                packageName = packageName,
                projectName = projectName,
                groupId = groupId,
                artifactId = artifactId,
                description = description,
                buildSystem = buildSystem
            )
        }
    }
}