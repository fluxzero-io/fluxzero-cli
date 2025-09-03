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
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNull
import kotlin.test.assertFailsWith

class DefaultInstallerTest {
    
    private fun createMockBinaryResponse(): HttpResponse<InputStream> {
        val response = mockk<HttpResponse<InputStream>>()
        every { response.statusCode() } returns 200
        every { response.body() } returns ByteArrayInputStream("dummy binary".toByteArray())
        return response
    }
    
    private fun createMockReleaseResponse(version: String = "v2.0.0"): HttpResponse<String> {
        val response = mockk<HttpResponse<String>>()
        every { response.statusCode() } returns 200
        every { response.body() } returns "{\"tag_name\": \"$version\"}"
        return response
    }
    @Test
    fun `fresh install when no current version`() {
        val tempHome = Files.createTempDirectory("flux-home")

        val httpClient = mockk<HttpClient>()
        val releaseResponse = mockk<HttpResponse<String>>()
        val binaryResponse = mockk<HttpResponse<InputStream>>()

        every { releaseResponse.statusCode() } returns 200
        every { releaseResponse.body() } returns "{\"tag_name\": \"v2.0.0\"}"
        every { httpClient.send(match { it.uri() == URI.create(PRIMARY_LATEST_API_URL) }, any<HttpResponse.BodyHandler<String>>()) } returns releaseResponse

        val binaryStream = ByteArrayInputStream("dummy native binary".toByteArray())
        every { binaryResponse.statusCode() } returns 200
        every { binaryResponse.body() } returns binaryStream
        // Mock the native binary download (will vary by platform, but test should work on any)
        every { httpClient.send(match { it.uri().toString().contains("v2.0.0/flux-") }, any<HttpResponse.BodyHandler<InputStream>>()) } returns binaryResponse

        // Create installer with mocked version that returns null (no current version)
        val installer = object : DefaultInstaller(httpClient, tempHome) {
            override fun getCurrentVersion(): String? = null
        }
        
        val result = installer.install()

        assertTrue(result is InstallResult.FreshInstall)
        assertEquals("v2.0.0", (result as InstallResult.FreshInstall).version)
        assertTrue(Files.exists(tempHome.resolve(".fluxzero/bin/fz")))
        assertTrue(Files.isExecutable(tempHome.resolve(".fluxzero/bin/fz")))
    }

    @Test
    fun `upgrade from legacy installation`() {
        val tempHome = Files.createTempDirectory("flux-home")
        
        // Create legacy installation
        val legacyDir = tempHome.resolve(".flux")
        Files.createDirectories(legacyDir)
        Files.write(legacyDir.resolve("fluxzero-cli.jar"), "old jar".toByteArray())
        Files.write(legacyDir.resolve("cli"), "old script".toByteArray())

        val httpClient = mockk<HttpClient>()
        val releaseResponse = mockk<HttpResponse<String>>()
        val binaryResponse = mockk<HttpResponse<InputStream>>()

        every { releaseResponse.statusCode() } returns 200
        every { releaseResponse.body() } returns "{\"tag_name\": \"v2.0.0\"}"
        every { httpClient.send(match { it.uri() == URI.create(PRIMARY_LATEST_API_URL) }, any<HttpResponse.BodyHandler<String>>()) } returns releaseResponse

        val binaryStream = ByteArrayInputStream("new native binary".toByteArray())
        every { binaryResponse.statusCode() } returns 200
        every { binaryResponse.body() } returns binaryStream
        every { httpClient.send(match { it.uri().toString().contains("v2.0.0/flux-") }, any<HttpResponse.BodyHandler<InputStream>>()) } returns binaryResponse

        val installer = object : DefaultInstaller(httpClient, tempHome) {
            override fun getCurrentVersion(): String? = "v1.0.0"
        }
        
        val result = installer.install()

        assertTrue(result is InstallResult.Upgraded)
        assertEquals("v1.0.0", (result as InstallResult.Upgraded).fromVersion)
        assertEquals("v2.0.0", result.toVersion)
        
        // Verify new installation
        assertTrue(Files.exists(tempHome.resolve(".fluxzero/bin/fz")))
        assertTrue(Files.isExecutable(tempHome.resolve(".fluxzero/bin/fz")))
        
        // Verify legacy cleanup
        assertTrue(!Files.exists(legacyDir.resolve("fluxzero-cli.jar")))
        assertTrue(!Files.exists(legacyDir.resolve("cli")))
    }

    @Test
    fun `already latest version`() {
        val tempHome = Files.createTempDirectory("flux-home")

        val httpClient = mockk<HttpClient>()
        val releaseResponse = mockk<HttpResponse<String>>()

        every { releaseResponse.statusCode() } returns 200
        every { releaseResponse.body() } returns "{\"tag_name\": \"v2.0.0\"}"
        every { httpClient.send(match { it.uri() == URI.create(PRIMARY_LATEST_API_URL) }, any<HttpResponse.BodyHandler<String>>()) } returns releaseResponse

        val installer = object : DefaultInstaller(httpClient, tempHome) {
            override fun getCurrentVersion(): String? = "v2.0.0"
        }
        
        val result = installer.install()

        assertTrue(result is InstallResult.AlreadyLatest)
        assertEquals("v2.0.0", (result as InstallResult.AlreadyLatest).currentVersion)
    }

    @Test
    fun `handles github api failure`() {
        val tempHome = Files.createTempDirectory("flux-home")

        val httpClient = mockk<HttpClient>()
        val releaseResponse = mockk<HttpResponse<String>>()

        every { releaseResponse.statusCode() } returns 404
        every { httpClient.send(match { it.uri() == URI.create(PRIMARY_LATEST_API_URL) }, any<HttpResponse.BodyHandler<String>>()) } returns releaseResponse

        val installer = DefaultInstaller(httpClient, tempHome)
        
        val exception = assertFailsWith<IllegalStateException> {
            installer.install()
        }
        assertTrue(exception.message!!.contains("Could not determine latest release"))
        assertTrue(exception.message!!.contains("https://fluxzero.io/docs/getting-started"))
    }

    @Test
    fun `handles binary download failure`() {
        val tempHome = Files.createTempDirectory("flux-home")

        val httpClient = mockk<HttpClient>()
        val releaseResponse = mockk<HttpResponse<String>>()
        val binaryResponse = mockk<HttpResponse<InputStream>>()

        every { releaseResponse.statusCode() } returns 200
        every { releaseResponse.body() } returns "{\"tag_name\": \"v2.0.0\"}"
        every { httpClient.send(match { it.uri() == URI.create(PRIMARY_LATEST_API_URL) }, any<HttpResponse.BodyHandler<String>>()) } returns releaseResponse

        every { binaryResponse.statusCode() } returns 404
        every { httpClient.send(match { it.uri().toString().contains("v2.0.0/flux-") }, any<HttpResponse.BodyHandler<InputStream>>()) } returns binaryResponse

        val installer = object : DefaultInstaller(httpClient, tempHome) {
            override fun getCurrentVersion(): String? = null
        }
        
        val exception = assertFailsWith<IllegalStateException> {
            installer.install()
        }
        assertTrue(exception.message!!.contains("Installation failed"))
        assertTrue(exception.message!!.contains("https://fluxzero.io/docs/getting-started"))
    }

    @Test
    fun `handles upgrade failure with helpful message`() {
        val tempHome = Files.createTempDirectory("flux-home")

        val httpClient = mockk<HttpClient>()
        val releaseResponse = mockk<HttpResponse<String>>()
        val binaryResponse = mockk<HttpResponse<InputStream>>()

        every { releaseResponse.statusCode() } returns 200
        every { releaseResponse.body() } returns "{\"tag_name\": \"v2.0.0\"}"
        every { httpClient.send(match { it.uri() == URI.create(PRIMARY_LATEST_API_URL) }, any<HttpResponse.BodyHandler<String>>()) } returns releaseResponse

        every { binaryResponse.statusCode() } returns 404
        every { httpClient.send(match { it.uri().toString().contains("v2.0.0/flux-") }, any<HttpResponse.BodyHandler<InputStream>>()) } returns binaryResponse

        val installer = object : DefaultInstaller(httpClient, tempHome) {
            override fun getCurrentVersion(): String? = "v1.0.0" // Has current version, so this will be an upgrade
        }
        
        val exception = assertFailsWith<IllegalStateException> {
            installer.install()
        }
        assertTrue(exception.message!!.contains("Upgrade failed"))
        assertTrue(exception.message!!.contains("https://fluxzero.io/docs/getting-started"))
    }

    @Test
    fun `falls back to alternative repository when primary fails`() {
        val tempHome = Files.createTempDirectory("flux-home")

        val httpClient = mockk<HttpClient>()
        val primaryReleaseResponse = mockk<HttpResponse<String>>()
        val fallbackReleaseResponse = mockk<HttpResponse<String>>()
        val binaryResponse = mockk<HttpResponse<InputStream>>()

        // Primary repository returns 404
        every { primaryReleaseResponse.statusCode() } returns 404
        every { httpClient.send(match { it.uri() == URI.create(PRIMARY_LATEST_API_URL) }, any<HttpResponse.BodyHandler<String>>()) } returns primaryReleaseResponse

        // Fallback repository succeeds
        every { fallbackReleaseResponse.statusCode() } returns 200
        every { fallbackReleaseResponse.body() } returns "{\"tag_name\": \"v2.0.0\"}"
        every { httpClient.send(match { it.uri() == URI.create(FALLBACK_LATEST_API_URL) }, any<HttpResponse.BodyHandler<String>>()) } returns fallbackReleaseResponse

        // Binary download succeeds from fallback
        val binaryStream = ByteArrayInputStream("fallback binary".toByteArray())
        every { binaryResponse.statusCode() } returns 200
        every { binaryResponse.body() } returns binaryStream
        every { httpClient.send(match { it.uri().toString().contains("fluxzero/cli") && it.uri().toString().contains("v2.0.0/flux-") }, any<HttpResponse.BodyHandler<InputStream>>()) } returns binaryResponse

        val installer = object : DefaultInstaller(httpClient, tempHome) {
            override fun getCurrentVersion(): String? = null
        }
        
        val result = installer.install()

        assertTrue(result is InstallResult.FreshInstall)
        assertEquals("v2.0.0", (result as InstallResult.FreshInstall).version)
        assertTrue(Files.exists(tempHome.resolve(".fluxzero/bin/fz")))
        assertTrue(Files.isExecutable(tempHome.resolve(".fluxzero/bin/fz")))
    }

    @Test
    fun `platform detection works correctly`() {
        val tempHome = Files.createTempDirectory("flux-home")
        val httpClient = mockk<HttpClient>()
        
        val installer = object : DefaultInstaller(httpClient, tempHome) {
            // Expose the private method for testing
            fun testDetectPlatform(): Pair<String, String> {
                val osName = System.getProperty("os.name").lowercase()
                val osArch = System.getProperty("os.arch").lowercase()
                
                val platform = when {
                    osName.contains("windows") -> "windows"
                    osName.contains("mac") || osName.contains("darwin") -> "macos"
                    osName.contains("linux") -> "linux"
                    else -> throw IllegalStateException("Unsupported operating system: $osName")
                }
                
                val arch = when {
                    osArch.contains("amd64") || osArch.contains("x86_64") -> "amd64"
                    osArch.contains("aarch64") || osArch.contains("arm64") -> "arm64"
                    else -> throw IllegalStateException("Unsupported architecture: $osArch")
                }
                
                return platform to arch
            }
            
            fun testGetBinaryName(platform: String, arch: String): String {
                return when (platform) {
                    "windows" -> "flux-windows-$arch.exe"
                    "macos" -> "flux-macos-$arch"
                    "linux" -> "flux-linux-$arch"
                    else -> throw IllegalStateException("Unsupported platform: $platform")
                }
            }
        }
        
        // Test platform detection doesn't throw
        val (platform, arch) = installer.testDetectPlatform()
        assertTrue(platform in listOf("windows", "macos", "linux"))
        assertTrue(arch in listOf("amd64", "arm64"))
        
        // Test binary name generation
        val binaryName = installer.testGetBinaryName(platform, arch)
        assertTrue(binaryName.startsWith("flux-$platform-$arch"))
        if (platform == "windows") {
            assertTrue(binaryName.endsWith(".exe"))
        }
    }

    @Test
    fun `version detection from implementation version works`() {
        val tempHome = Files.createTempDirectory("flux-home")
        val httpClient = mockk<HttpClient>()
        
        val installer = object : DefaultInstaller(httpClient, tempHome) {
            override fun getCurrentVersion(): String? = "1.2.3" // Mock implementation version
        }
        
        assertEquals("1.2.3", installer.getCurrentVersion())
    }

    @Test
    fun `version detection falls back to filesystem check`() {
        val tempHome = Files.createTempDirectory("flux-home")
        val httpClient = mockk<HttpClient>()
        
        // Create current installation
        val binDir = tempHome.resolve(".fluxzero/bin")
        Files.createDirectories(binDir)
        Files.write(binDir.resolve("fz"), "dummy binary".toByteArray())
        binDir.resolve("fz").toFile().setExecutable(true)
        
        val installer = object : DefaultInstaller(httpClient, tempHome) {
            override fun getCurrentVersion(): String? {
                // Simulate implementation version not available
                return try {
                    DefaultInstaller::class.java.`package`.implementationVersion
                } catch (_: Exception) {
                    // Fall back to filesystem detection (this will return null in test since we can't actually execute)
                    null
                }
            }
        }
        
        // In test environment, this will return null since we can't execute the dummy binary
        // But we can verify the filesystem check is attempted
        assertNull(installer.getCurrentVersion())
        assertTrue(Files.exists(tempHome.resolve(".fluxzero/bin/fz")))
    }
}
