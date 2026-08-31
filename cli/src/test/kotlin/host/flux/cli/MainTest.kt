package host.flux.cli

import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.testing.test
import host.flux.cli.commands.Dev
import host.flux.cli.commands.Init
import host.flux.cli.commands.Mcp
import host.flux.cli.commands.Upgrade
import host.flux.cli.commands.Version
import host.flux.cli.commands.templates.Templates
import java.net.ConnectException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MainTest {
    @Test
    fun `uses nested actionable error message`() {
        val failure = IllegalStateException(null, IllegalArgumentException("download failed"))

        assertEquals("download failed", failure.actionableMessage())
    }

    @Test
    fun `falls back to exception type when every message is absent`() {
        assertEquals("ConnectException", ConnectException().actionableMessage())
    }

    @Test
    fun `root help uses current Fluxzero branding and task descriptions`() {
        val result = FluxCli()
            .subcommands(Init(), Dev(), Mcp(), Version(), Upgrade(), Templates())
            .test("--help")

        assertEquals(0, result.statusCode)
        assertTrue(result.output.contains("Usage: fz"), result.output)
        assertTrue(result.output.contains("Build, run, and manage Fluxzero applications"), result.output)
        assertTrue(result.output.contains("Create a new Fluxzero application"), result.output)
        assertTrue(result.output.contains("Run and control the local Fluxzero development environment"), result.output)
        assertTrue(result.output.contains("Connect an agent to the local Fluxzero development environment"), result.output)
        assertTrue(!result.output.contains("flux-cli"), result.output)
    }
}
