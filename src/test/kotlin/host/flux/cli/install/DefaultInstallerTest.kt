package host.flux.cli.install

import io.mockk.every
import io.mockk.mockk
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DefaultInstallerTest {
    @Test
    fun `fresh install when no current version`() {
        val tempHome = Files.createTempDirectory("flux-home")

        val httpClient = mockk<HttpClient>()
        val releaseResponse = mockk<HttpResponse<String>>()
        val jarResponse = mockk<HttpResponse<InputStream>>()

        every { releaseResponse.statusCode() } returns 200
        every { releaseResponse.body() } returns "{\"tag_name\": \"v2.0.0\"}"
        every { httpClient.send(match { it.uri() == URI.create(LATEST_API_URL) }, any<HttpResponse.BodyHandler<String>>()) } returns releaseResponse

        val jarStream = ByteArrayInputStream("dummy".toByteArray())
        every { jarResponse.statusCode() } returns 200
        every { jarResponse.body() } returns jarStream
        every { httpClient.send(match { it.uri().toString().contains("v2.0.0/fluxzero-cli.jar") }, any<HttpResponse.BodyHandler<InputStream>>()) } returns jarResponse

        // Create installer with mocked version that returns null (no current version)
        val installer = object : DefaultInstaller(httpClient, tempHome) {
            override fun getCurrentVersion(): String? = null
        }
        
        val result = installer.install()

        assertTrue(result is InstallResult.FreshInstall)
        assertEquals("v2.0.0", (result as InstallResult.FreshInstall).version)
        assertTrue(Files.exists(tempHome.resolve(".fluxzero/fluxzero-cli.jar")))
        assertTrue(Files.exists(tempHome.resolve(".fluxzero/cli")))
    }
}
