package host.flux.projectfiles

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.ConnectException
import java.net.UnknownHostException
import java.net.http.HttpTimeoutException
import java.nio.file.Path
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
}
