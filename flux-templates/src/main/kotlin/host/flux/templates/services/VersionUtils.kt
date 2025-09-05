package host.flux.templates.services

object VersionUtils {
    fun getCurrentVersion(): String {
        return this::class.java.`package`.implementationVersion ?: "dev"
    }
}