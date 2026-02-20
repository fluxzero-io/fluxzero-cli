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
import kotlin.test.assertIs

class DefaultProjectFilesServiceTest {

    @TempDir
    lateinit var tempDir: Path

    private val gitHubClient = mockk<GitHubReleaseClient>()
    private val service = DefaultProjectFilesService(gitHubClient)

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
    fun `returns Failed when GitHub API returns an error`() {
        every { gitHubClient.downloadProjectFiles(any(), any()) } throws
            GitHubApiException("404 Not Found")

        val result = service.syncProjectFiles(
            projectDir = tempDir,
            language = Language.KOTLIN,
            version = "1.75.1"
        )

        assertIs<SyncResult.Failed>(result)
        assert(result.error.contains("404 Not Found"))
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
        val fluxzeroDir = tempDir.resolve(".fluxzero")
        Files.createDirectories(fluxzeroDir)
        Files.writeString(fluxzeroDir.resolve(".sync-version"), "1.75.1:kotlin")

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
        val fluxzeroDir = tempDir.resolve(".fluxzero")
        Files.createDirectories(fluxzeroDir)
        Files.writeString(fluxzeroDir.resolve(".sync-version"), "1.74.0:kotlin")

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
        val fluxzeroDir = tempDir.resolve(".fluxzero")
        Files.createDirectories(fluxzeroDir)
        Files.writeString(fluxzeroDir.resolve(".sync-version"), "1.75.1:java")

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
    }

    @Test
    fun `downloads when forceUpdate is true despite matching version`() {
        val fluxzeroDir = tempDir.resolve(".fluxzero")
        Files.createDirectories(fluxzeroDir)
        Files.writeString(fluxzeroDir.resolve(".sync-version"), "1.75.1:kotlin")

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
    fun `writes version file after successful sync`() {
        every { gitHubClient.downloadProjectFiles(Language.KOTLIN, "1.75.1") } returns
            buildProjectFilesZip("kotlin")

        service.syncProjectFiles(
            projectDir = tempDir,
            language = Language.KOTLIN,
            version = "1.75.1"
        )

        val syncVersionFile = tempDir.resolve(".fluxzero/.sync-version")
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
