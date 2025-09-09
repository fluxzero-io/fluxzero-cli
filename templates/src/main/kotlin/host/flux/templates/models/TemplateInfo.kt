package host.flux.templates.models

data class TemplateInfo(
    val name: String,
    val description: String = ""
)

enum class BuildSystem(val displayName: String) {
    MAVEN("maven"),
    GRADLE("gradle")
}

data class ScaffoldProject(
    val template: String,
    val name: String,
    val outputDir: String? = null,
    val initGit: Boolean = false,
    val packageName: String = "com.example.app",
    val groupId: String? = null,
    val buildSystem: BuildSystem? = null
)

data class ScaffoldResult(
    val success: Boolean,
    val message: String,
    val outputPath: String? = null,
    val error: String? = null
)