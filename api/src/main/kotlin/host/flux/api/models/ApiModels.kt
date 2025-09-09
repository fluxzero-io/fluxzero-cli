package host.flux.api.models

import kotlinx.serialization.Serializable

@Serializable
data class InitRequest(
    val template: String,
    val name: String,
    val initGit: Boolean = false,
    val packageName: String = "com.example.app",
    val groupId: String? = null,
    val buildSystem: String? = null // "maven" or "gradle"
)

@Serializable
data class InitFailure(
    val error: String
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
