package host.flux.cli.services

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

object UpdateService {
    private const val LATEST_RELEASE_URL = "https://api.github.com/repos/fluxzero-io/fluxzero-cli/releases/latest"

    fun isNewer(current: String, latest: String): Boolean {
        val curParts = parseVersion(current)
        val latestParts = parseVersion(latest)
        val max = maxOf(curParts.size, latestParts.size)
        for (i in 0 until max) {
            val c = curParts.getOrNull(i) ?: 0
            val l = latestParts.getOrNull(i) ?: 0
            if (l > c) return true
            if (l < c) return false
        }
        return false
    }

    private fun parseVersion(v: String): List<Int> =
        v.removePrefix("v").split("[.-]".toRegex()).mapNotNull { it.toIntOrNull() }

    fun getLatestVersion(): String? {
        return try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create(LATEST_RELEASE_URL))
                .header("Accept", "application/json")
                .build()
            val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() == 200) {
                val tagRegex = "\"tag_name\"\\s*:\\s*\"([^\"]+)\"".toRegex()
                tagRegex.find(response.body())?.groupValues?.get(1)
            } else null
        } catch (_: Exception) {
            null
        }
    }

    fun checkForUpdates(currentVersion: String): UpdateInfo {
        val latest = getLatestVersion()
        return if (latest != null && isNewer(currentVersion, latest)) {
            UpdateInfo(hasUpdate = true, currentVersion = currentVersion, latestVersion = latest)
        } else {
            UpdateInfo(hasUpdate = false, currentVersion = currentVersion, latestVersion = latest)
        }
    }
}

data class UpdateInfo(
    val hasUpdate: Boolean,
    val currentVersion: String,
    val latestVersion: String?
)