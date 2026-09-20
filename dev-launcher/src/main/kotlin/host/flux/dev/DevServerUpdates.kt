package host.flux.dev

import java.nio.file.Path

/** Read/prepare updates without touching running environments or project version selection. */
class DevServerUpdates(
    private val latest: () -> String = { DevServerVersionResolver().latestCompatible() },
    private val artifact: (String) -> Path = { DevServerArtifactCache().resolve(it) }
) {
    fun check(current: String, pinned: Boolean = false): Map<String, String> {
        val running = StableVersion.parse(current)
        if (pinned || running == null || running.major != SUPPORTED_DEV_SERVER_MAJOR)
            return mapOf("status" to "pinned", "currentVersion" to current)
        val candidate = latest()
        val version = StableVersion.parse(candidate)
        require(version != null && version.major == SUPPORTED_DEV_SERVER_MAJOR) { "Incompatible update version" }
        return mapOf("status" to if (version > running) "available" else "current",
            "currentVersion" to current, "latestVersion" to candidate)
    }

    fun prepare(current: String, requested: String, pinned: Boolean = false): Map<String, String> {
        val status = check(current, pinned)
        require(status["status"] == "available" && status["latestVersion"] == requested) {
            "The selected update is no longer available. Check for updates again."
        }
        return status + ("artifact" to artifact(requested).toAbsolutePath().normalize().toString())
    }
}
