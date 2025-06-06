package host.flux.cli

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

object UpdateChecker {
    private const val LATEST_RELEASE_URL = "https://api.github.com/repos/flux-capacitor-io/flux-cli/releases/latest"

    internal fun isNewer(current: String, latest: String): Boolean {
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

    fun notifyIfNewVersion(currentVersion: String, notify: (String) -> Unit = ::println) {
        try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create(LATEST_RELEASE_URL))
                .header("Accept", "application/json")
                .build()
            val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() == 200) {
                val tagRegex = "\"tag_name\"\\s*:\\s*\"([^\"]+)\"".toRegex()
                val latest = tagRegex.find(response.body())?.groupValues?.get(1)
                if (latest != null && isNewer(currentVersion, latest)) {
                    notify("A new version of flux-cli is available: $latest (current: $currentVersion)")
                }
            }
        } catch (_: Exception) {
            // Fail silently if update check fails
        }
    }
}

