package host.flux.projectfiles

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.net.ConnectException
import java.net.UnknownHostException
import java.net.http.HttpTimeoutException
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DefaultProjectFilesServiceTest {

    @TempDir
    lateinit var tempDir: Path

    private val gitHubClient = mockk<GitHubReleaseClient>()
    private val service = DefaultProjectFilesService(gitHubClient)

    private val fluxzeroDir: Path
        get() = tempDir.resolve(".fluxzero")

    private val agentsDir: Path
        get() = fluxzeroDir.resolve("agents")

    @Test
    fun `returns Skipped when SDK version cannot be detected`() {
        val result = service.syncProjectFiles(
            projectDir = tempDir,
            language = Language.KOTLIN
        )

        assertIs<SyncResult.Skipped>(result)
        assert(result.reason.contains("No Fluxzero SDK version"))
        verify(exactly = 0) { gitHubClient.downloadProjectFiles(any(), any()) }
    }

    @Test
    fun `returns Skipped for Gradle unknown SDK version sentinel`() {
        val result = service.syncProjectFiles(
            projectDir = tempDir,
            language = Language.KOTLIN,
            version = DefaultProjectFilesService.UNKNOWN_VERSION
        )

        assertIs<SyncResult.Skipped>(result)
        assert(result.reason.contains("No Fluxzero SDK version"))
        verify(exactly = 0) { gitHubClient.downloadProjectFiles(any(), any()) }
    }

    @Test
    fun `returns Skipped for local snapshot SDK version`() {
        val result = service.syncProjectFiles(
            projectDir = tempDir,
            language = Language.KOTLIN,
            version = "0-SNAPSHOT"
        )

        assertIs<SyncResult.Skipped>(result)
        assert(result.reason.contains("snapshot"))
        verify(exactly = 0) { gitHubClient.downloadProjectFiles(any(), any()) }
    }

    @Test
    fun `returns Skipped for non-release SDK version`() {
        val result = service.syncProjectFiles(
            projectDir = tempDir,
            language = Language.KOTLIN,
            version = "local-dev"
        )

        assertIs<SyncResult.Skipped>(result)
        assert(result.reason.contains("not a supported release version"))
        verify(exactly = 0) { gitHubClient.downloadProjectFiles(any(), any()) }
    }

    @Test
    fun `returns Skipped when connection is refused`() {
        every { gitHubClient.downloadProjectFiles(any(), any()) } throws
            ConnectException("Connection refused")

        val result = service.syncProjectFiles(
            projectDir = tempDir,
            language = Language.KOTLIN,
            version = "1.75.1"
        )

        assertIs<SyncResult.Skipped>(result)
        assert(result.reason.contains("ConnectException"))
    }

    @Test
    fun `returns Skipped when DNS resolution fails`() {
        every { gitHubClient.downloadProjectFiles(any(), any()) } throws
            UnknownHostException("api.github.com")

        val result = service.syncProjectFiles(
            projectDir = tempDir,
            language = Language.KOTLIN,
            version = "1.75.1"
        )

        assertIs<SyncResult.Skipped>(result)
        assert(result.reason.contains("UnknownHostException"))
    }

    @Test
    fun `returns Skipped when request times out`() {
        every { gitHubClient.downloadProjectFiles(any(), any()) } throws
            HttpTimeoutException("request timed out")

        val result = service.syncProjectFiles(
            projectDir = tempDir,
            language = Language.KOTLIN,
            version = "1.75.1"
        )

        assertIs<SyncResult.Skipped>(result)
        assert(result.reason.contains("HttpTimeoutException"))
    }

    @Test
    fun `returns Skipped when GitHub API returns an error`() {
        every { gitHubClient.downloadProjectFiles(any(), any()) } throws
            GitHubApiException("404 Not Found")

        val result = service.syncProjectFiles(
            projectDir = tempDir,
            language = Language.KOTLIN,
            version = "1.75.1"
        )

        assertIs<SyncResult.Skipped>(result)
        assert(result.reason.contains("404 Not Found"))
    }

    @Test
    fun `returns Failed on unexpected RuntimeException`() {
        every { gitHubClient.downloadProjectFiles(any(), any()) } throws
            RuntimeException("unexpected")

        val result = service.syncProjectFiles(
            projectDir = tempDir,
            language = Language.KOTLIN,
            version = "1.75.1"
        )

        assertIs<SyncResult.Failed>(result)
        assert(result.error.contains("unexpected"))
    }

    // --- Sync-version caching tests ---

    @Test
    fun `returns UpToDate when version file matches`() {
        Files.createDirectories(agentsDir)
        Files.writeString(agentsDir.resolve(".sync-version"), "1.75.1:kotlin")

        val result = service.syncProjectFiles(
            projectDir = tempDir,
            language = Language.KOTLIN,
            version = "1.75.1"
        )

        assertIs<SyncResult.UpToDate>(result)
        assertEquals("1.75.1", result.version)
        verify(exactly = 0) { gitHubClient.downloadProjectFiles(any(), any()) }
    }

    @Test
    fun `downloads when version file has different version`() {
        Files.createDirectories(agentsDir)
        Files.writeString(agentsDir.resolve(".sync-version"), "1.74.0:kotlin")

        every { gitHubClient.downloadProjectFiles(Language.KOTLIN, "1.75.1") } returns
            buildProjectFilesZip("kotlin")

        val result = service.syncProjectFiles(
            projectDir = tempDir,
            language = Language.KOTLIN,
            version = "1.75.1"
        )

        assertIs<SyncResult.Updated>(result)
        assertEquals("1.75.1", result.version)
    }

    @Test
    fun `downloads when version file has different language`() {
        Files.createDirectories(agentsDir)
        Files.writeString(agentsDir.resolve(".sync-version"), "1.75.1:java")

        every { gitHubClient.downloadProjectFiles(Language.KOTLIN, "1.75.1") } returns
            buildProjectFilesZip("kotlin")

        val result = service.syncProjectFiles(
            projectDir = tempDir,
            language = Language.KOTLIN,
            version = "1.75.1"
        )

        assertIs<SyncResult.Updated>(result)
        assertEquals("1.75.1", result.version)
    }

    @Test
    fun `downloads when no version file exists`() {
        every { gitHubClient.downloadProjectFiles(Language.KOTLIN, "1.75.1") } returns
            buildProjectFilesZip("kotlin")

        val result = service.syncProjectFiles(
            projectDir = tempDir,
            language = Language.KOTLIN,
            version = "1.75.1"
        )

        assertIs<SyncResult.Updated>(result)
        assertEquals("1.75.1", result.version)
        assertTrue(Files.exists(fluxzeroDir))
        assertTrue(Files.exists(agentsDir.resolve("AGENTS.md")))
    }

    @Test
    fun `downloads when forceUpdate is true despite matching version`() {
        Files.createDirectories(agentsDir)
        Files.writeString(agentsDir.resolve(".sync-version"), "1.75.1:kotlin")

        every { gitHubClient.downloadProjectFiles(Language.KOTLIN, "1.75.1") } returns
            buildProjectFilesZip("kotlin")

        val result = service.syncProjectFiles(
            projectDir = tempDir,
            forceUpdate = true,
            language = Language.KOTLIN,
            version = "1.75.1"
        )

        assertIs<SyncResult.Updated>(result)
        verify(exactly = 1) { gitHubClient.downloadProjectFiles(Language.KOTLIN, "1.75.1") }
    }

    @Test
    fun `updates agents directory without deleting other fluxzero files`() {
        Files.createDirectories(agentsDir)
        Files.writeString(fluxzeroDir.resolve("config.yaml"), "customer config")
        Files.writeString(agentsDir.resolve(".sync-version"), "1.74.0:kotlin")
        Files.writeString(agentsDir.resolve("stale.md"), "stale")

        every { gitHubClient.downloadProjectFiles(Language.KOTLIN, "1.75.1") } returns
            buildProjectFilesZip("kotlin")

        val result = service.syncProjectFiles(
            projectDir = tempDir,
            language = Language.KOTLIN,
            version = "1.75.1"
        )

        assertIs<SyncResult.Updated>(result)
        assertTrue(Files.exists(fluxzeroDir.resolve("config.yaml")))
        assertEquals("customer config", Files.readString(fluxzeroDir.resolve("config.yaml")))
        assertFalse(Files.exists(agentsDir.resolve("stale.md")))
        assertTrue(Files.exists(agentsDir.resolve("AGENTS.md")))
    }

    @Test
    fun `writes version file after successful sync`() {
        every { gitHubClient.downloadProjectFiles(Language.KOTLIN, "1.75.1") } returns
            buildProjectFilesZip("kotlin")

        service.syncProjectFiles(
            projectDir = tempDir,
            language = Language.KOTLIN,
            version = "1.75.1"
        )

        val syncVersionFile = tempDir.resolve(".fluxzero/agents/.sync-version")
        assert(Files.exists(syncVersionFile)) { "Expected .sync-version file to exist" }
        assertEquals("1.75.1:kotlin", Files.readString(syncVersionFile))
    }

    /**
     * Builds a minimal ZIP archive containing a single dummy file under the given language prefix,
     * matching the format expected by [ProjectFilesExtractor].
     */
    private fun buildProjectFilesZip(languagePrefix: String): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry("$languagePrefix/"))
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("$languagePrefix/AGENTS.md"))
            zos.write("# Agents\n".toByteArray())
            zos.closeEntry()
        }
        return baos.toByteArray()
    }
}
