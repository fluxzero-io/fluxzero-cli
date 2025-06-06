package host.flux.cli

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

object UpdateChecker {
    private const val LATEST_RELEASE_URL = "https://api.github.com/repos/flux-capacitor-io/flux-cli/releases/latest"

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
                if (latest != null && latest != currentVersion) {
                    notify("A new version of flux-cli is available: $latest (current: $currentVersion)")
                }
            }
        } catch (_: Exception) {
            // Fail silently if update check fails
        }
    }
}

