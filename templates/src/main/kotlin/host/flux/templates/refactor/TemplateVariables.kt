package host.flux.templates.refactor

import host.flux.templates.models.BuildSystem

data class TemplateVariables(
    val packageName: String,
    val projectName: String,
    val groupId: String? = null,
    val buildSystem: BuildSystem? = null
) {
    /**
     * Get the final group ID, using packageName as fallback if groupId is null
     */
    val finalGroupId: String get() = groupId ?: packageName
    
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
            buildSystem: BuildSystem? = null
        ): TemplateVariables {
            return TemplateVariables(
                packageName = packageName,
                projectName = projectName,
                groupId = groupId,
                buildSystem = buildSystem
            )
        }
    }
}