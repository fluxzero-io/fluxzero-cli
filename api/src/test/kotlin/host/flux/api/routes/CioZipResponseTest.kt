package host.flux.api.routes

import host.flux.api.configurePlugins
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.zip.ZipInputStream
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CioZipResponseTest {
    @Test
    fun `CIO sends the complete generated ZIP before temporary file cleanup`() = runBlocking {
        val server = embeddedServer(CIO, host = "127.0.0.1", port = 0) {
            configurePlugins()
            configureRoutes()
        }.start(wait = false)
        try {
            val port = server.engine.resolvedConnectors().single().port
            val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/api/init"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""
                    {"template":"flux-basic-java","name":"transport-proof", "packageName":"com.example.transport",
                     "groupId":"com.example","artifactId":"transport-proof","buildSystem":"maven"}
                """.trimIndent())).build()
            HttpClient.newHttpClient().use { client ->
                val response = client.send(request, HttpResponse.BodyHandlers.ofByteArray())
                assertEquals(200, response.statusCode())
                assertTrue(response.headers().firstValue("Content-Disposition").orElse("").contains("transport-proof.zip"))
                val files = mutableSetOf<String>()
                ZipInputStream(ByteArrayInputStream(response.body())).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        files += entry.name
                        zip.readAllBytes() // Read every entry and verify its CRC before the connection closes.
                    }
                }
                assertTrue(files.any { it == "pom.xml" || it.endsWith("/pom.xml") })
                assertTrue(files.any { it.endsWith("src/main/java/com/example/transport/App.java") })
            }
        } finally {
            server.stop(100, 5000)
        }
    }
}
