package host.flux.api.models

import kotlinx.serialization.Serializable

@Serializable
data class InitRequest(
    val template: String,
    val name: String,
    val outputDir: String? = null,
    val initGit: Boolean = false
)

@Serializable
data class InitResponse(
    val success: Boolean,
    val message: String,
    val outputPath: String? = null,
    val error: String? = null
)

@Serializable
data class TemplateResponse(
    val name: String,
    val description: String = ""
)

@Serializable
data class VersionResponse(
    val currentVersion: String,
    val latestVersion: String? = null,
    val hasUpdate: Boolean = false
)
