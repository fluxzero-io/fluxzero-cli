package host.flux.templates.models

data class TemplateInfo(
    val name: String,
    val description: String = ""
)

data class InitRequest(
    val template: String,
    val name: String,
    val outputDir: String? = null,
    val initGit: Boolean = false,
    val packageName: String = "com.example.app",
    val groupId: String? = null
)

data class InitResult(
    val success: Boolean,
    val message: String,
    val outputPath: String? = null,
    val error: String? = null
)