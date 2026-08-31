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
            .subcommands(Init(), Dev(), Templates(), Upgrade(), Mcp(), Version())
            .test("--help")

        assertEquals(0, result.statusCode)
        assertTrue(result.output.contains("Usage: fz"), result.output)
        assertTrue(result.output.contains("Build, run, and manage Fluxzero applications"), result.output)
        assertTrue(result.output.contains("Create a new Fluxzero application"), result.output)
        assertTrue(result.output.contains("Run and control the local Fluxzero development environment"), result.output)
        assertTrue(result.output.contains("Upgrade the Fluxzero CLI"), result.output)
        assertTrue(result.output.contains("-V, --version"), result.output)
        assertTrue(!result.output.contains("Connect an agent to the local Fluxzero development environment"), result.output)
        assertTrue(!result.output.contains("Print the installed Fluxzero CLI version"), result.output)
        assertTrue(result.output.indexOf("Commands:") < result.output.indexOf("Options:"), result.output)
        assertTrue(!result.output.contains("flux-cli"), result.output)
    }

    @Test
    fun `standard version option and compatible command remain available`() {
        val command = FluxCli().subcommands(Version())

        val optionResult = command.test("--version")
        val commandResult = command.test("version")

        assertEquals(0, optionResult.statusCode)
        assertEquals(0, commandResult.statusCode)
        assertEquals("dev", optionResult.output.trim())
        assertEquals("dev", commandResult.output.trim())
    }

    @Test
    fun `version and upgrade skip the redundant startup update check`() {
        assertTrue(!shouldCheckForUpdates(arrayOf("--version")))
        assertTrue(!shouldCheckForUpdates(arrayOf("-V")))
        assertTrue(!shouldCheckForUpdates(arrayOf("version")))
        assertTrue(!shouldCheckForUpdates(arrayOf("upgrade")))
        assertTrue(shouldCheckForUpdates(arrayOf("dev")))
    }
}
