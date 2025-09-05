package host.flux.templates.services

object VersionService {
    
    fun getCurrentVersion(): String {
        return this::class.java.`package`.implementationVersion ?: "dev"
    }
    
    fun getVersionInfo(): VersionInfo {
        val current = getCurrentVersion()
        val updateInfo = UpdateService.checkForUpdates(current)
        
        return VersionInfo(
            currentVersion = current,
            latestVersion = updateInfo.latestVersion,
            hasUpdate = updateInfo.hasUpdate
        )
    }
}

data class VersionInfo(
    val currentVersion: String,
    val latestVersion: String?,
    val hasUpdate: Boolean
)