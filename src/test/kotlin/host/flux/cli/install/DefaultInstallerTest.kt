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
    fun `downloads jar and script`() {
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
        every { httpClient.send(match { it.uri().toString().contains("v2.0.0/flux-cli.jar") }, any<HttpResponse.BodyHandler<InputStream>>()) } returns jarResponse

        val installer = DefaultInstaller(httpClient, tempHome)
        val version = installer.installLatest()

        assertEquals("v2.0.0", version)
        assertTrue(Files.exists(tempHome.resolve(".flux/flux-cli.jar")))
        assertTrue(Files.exists(tempHome.resolve(".flux/cli")))
    }
}
