package host.flux.cli.services

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

class InstallationServiceTest {
    @TempDir
    lateinit var homeDirectory: Path

    @Test
    fun `fresh install creates fz and fluxzero commands`() {
        val service = service(currentVersion = null)

        assertTrue(service.install() is InstallResult.FreshInstall)

        val binary = homeDirectory.resolve(".fluxzero/bin/fz")
        val alias = homeDirectory.resolve(".fluxzero/bin/fluxzero")
        assertTrue(Files.isExecutable(binary))
        assertTrue(Files.isExecutable(alias))
        assertContentEquals(Files.readAllBytes(binary), Files.readAllBytes(alias))
    }

    @Test
    fun `already current installation repairs missing fluxzero alias`() {
        val binary = homeDirectory.resolve(".fluxzero/bin/fz")
        Files.createDirectories(binary.parent)
        Files.writeString(binary, "installed binary")
        binary.toFile().setExecutable(true)
        val service = service(currentVersion = "v2.0.0", includeBinaryDownload = false)

        assertTrue(service.install() is InstallResult.AlreadyLatest)

        val alias = binary.resolveSibling("fluxzero")
        assertTrue(Files.isExecutable(alias))
        assertContentEquals(Files.readAllBytes(binary), Files.readAllBytes(alias))
    }

    private fun service(currentVersion: String?, includeBinaryDownload: Boolean = true): DefaultInstallationService {
        val httpClient = mockk<HttpClient>()
        val releaseResponse = mockk<HttpResponse<String>>()
        every { releaseResponse.statusCode() } returns 200
        every { releaseResponse.body() } returns "{\"tag_name\":\"v2.0.0\"}"
        every {
            httpClient.send(
                match { it.uri() == URI.create(PRIMARY_LATEST_API_URL) },
                any<HttpResponse.BodyHandler<String>>()
            )
        } returns releaseResponse
        if (includeBinaryDownload) {
            val binaryResponse = mockk<HttpResponse<InputStream>>()
            every { binaryResponse.statusCode() } returns 200
            every { binaryResponse.body() } returns ByteArrayInputStream("native binary".toByteArray())
            every {
                httpClient.send(
                    match { it.uri().toString().contains("/releases/download/v2.0.0/") },
                    any<HttpResponse.BodyHandler<InputStream>>()
                )
            } returns binaryResponse
        }
        return object : DefaultInstallationService(httpClient, homeDirectory) {
            override fun getCurrentVersion(): String? = currentVersion
        }
    }
}
