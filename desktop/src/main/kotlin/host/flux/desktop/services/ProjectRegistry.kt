package host.flux.desktop.services

import host.flux.desktop.model.GeneratedProject
import host.flux.desktop.model.RegistryState
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

class ProjectRegistry(
    private val registryFile: Path,
    private val json: Json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }
) {
    fun listProjects(): List<GeneratedProject> {
        if (!Files.isRegularFile(registryFile)) {
            return emptyList()
        }
        return try {
            json.decodeFromString<RegistryState>(Files.readString(registryFile)).projects
                .sortedByDescending { it.generatedAt }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveProject(project: GeneratedProject): List<GeneratedProject> {
        val current = listProjects()
        val updated = (listOf(project) + current.filterNot { it.path == project.path })
            .sortedByDescending { it.generatedAt }
        write(RegistryState(updated))
        return updated
    }

    fun refreshSdkVersion(projectId: String, sdkVersion: String?): List<GeneratedProject> {
        val updated = listProjects().map {
            if (it.id == projectId) it.copy(sdkVersion = sdkVersion) else it
        }
        write(RegistryState(updated))
        return updated
    }

    private fun write(state: RegistryState) {
        Files.createDirectories(registryFile.parent)
        val temp = registryFile.resolveSibling("${registryFile.fileName}.tmp")
        Files.writeString(temp, json.encodeToString(state))
        try {
            Files.move(temp, registryFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        } catch (_: Exception) {
            Files.move(temp, registryFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
