package host.flux.cli.commands

import com.github.ajalt.clikt.testing.test
import com.fasterxml.jackson.databind.ObjectMapper
import host.flux.cli.shouldCheckForUpdates
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class AuthTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `status has no network credential or filesystem side effects`() {
        val previous = System.getProperty("fluxzero.account.directory")
        System.setProperty("fluxzero.account.directory", directory.resolve("account").toString())
        try {
            val result = Auth().test("status --json")
            assertEquals(0, result.statusCode, result.output)
            assertEquals("signed_out", ObjectMapper().readTree(result.output).path("state").asText())
            assertFalse(Files.exists(directory.resolve("account")))
            assertFalse(shouldCheckForUpdates(arrayOf("auth", "status", "--json")))
        } finally {
            if (previous == null) System.clearProperty("fluxzero.account.directory") else System.setProperty("fluxzero.account.directory", previous)
        }
    }

    @Test
    fun `invalid account configuration returns safe JSON without raw settings`() {
        val previous = System.getProperty("fluxzero.account.issuer")
        System.setProperty("fluxzero.account.issuer", "http://untrusted-private-setting.example.test")
        try {
            val result = Auth().test("status --json")
            assertEquals(1, result.statusCode)
            assertEquals("configuration_unavailable", ObjectMapper().readTree(result.output).path("error").path("code").asText())
            assertFalse(result.output.contains("untrusted-private-setting"))
        } finally {
            if (previous == null) System.clearProperty("fluxzero.account.issuer") else System.setProperty("fluxzero.account.issuer", previous)
        }
    }
}
