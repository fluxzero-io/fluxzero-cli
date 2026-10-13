package host.flux.cli.commands

import com.github.ajalt.clikt.testing.test
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import host.flux.cli.services.InstallResult
import host.flux.cli.services.InstallationService
import host.flux.cli.update.CliUpdateSettings
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.Test
import kotlin.test.assertTrue

class UpgradeTest {
    @TempDir lateinit var directory: Path

    private fun upgrade(installer: InstallationService) = Upgrade(installer, CliUpdateSettings(directory))

    @Test
    fun `configuring automatic updates preserves opt-outs without invoking the installer`() {
        val installer = mockk<InstallationService>()
        for ((action, expected) in listOf("status" to "unset (disabled)", "enable-if-unset" to "enabled",
            "disable" to "disabled", "enable-if-unset" to "disabled", "enable" to "enabled")) {
            val result = upgrade(installer).test(listOf("--auto-update", action))
            assertEquals(0, result.statusCode, result.output)
            assertTrue(result.stdout.contains("Automatic CLI updates: $expected"), result.stdout)
        }
        verify(exactly = 0) { installer.install() }
    }
    @Test
    fun `upgrades when newer version is available`() {
        val installer = mockk<InstallationService>()
        every { installer.install() } returns InstallResult.Upgraded("v1.0.0", "v1.2.3")

        val cmd = upgrade(installer)
        val result = cmd.test(emptyList())

        verify(exactly = 1) { installer.install() }
        assertTrue(result.stdout.contains("Checking Fluxzero CLI installation..."))
        assertTrue(result.stdout.contains("✅ Fluxzero CLI upgraded from v1.0.0 to v1.2.3"))
    }

    @Test
    fun `shows already up to date message when no upgrade needed`() {
        val installer = mockk<InstallationService>()
        every { installer.install() } returns InstallResult.AlreadyLatest("v1.2.3")

        val cmd = upgrade(installer)
        val result = cmd.test(emptyList())

        verify(exactly = 1) { installer.install() }
        assertTrue(result.stdout.contains("Checking Fluxzero CLI installation..."))
        assertTrue(result.stdout.contains("Fluxzero CLI is already up to date (current version: v1.2.3)"))
    }

    @Test
    fun `shows fresh install message for new installation`() {
        val installer = mockk<InstallationService>()
        every { installer.install() } returns InstallResult.FreshInstall("v1.2.3")

        val cmd = upgrade(installer)
        val result = cmd.test(emptyList())

        verify(exactly = 1) { installer.install() }
        assertTrue(result.stdout.contains("Checking Fluxzero CLI installation..."))
        assertTrue(result.stdout.contains("✅ Fluxzero CLI installed (version: v1.2.3)"))
    }

    @Test
    fun `reports successful package manager upgrade`() {
        val installer = mockk<InstallationService>()
        every { installer.install() } returns InstallResult.ManagedUpgrade("Homebrew")

        val result = upgrade(installer).test(emptyList())

        verify(exactly = 1) { installer.install() }
        assertTrue(result.stdout.contains("Fluxzero CLI update completed using Homebrew"))
    }

    @Test
    fun `shows clean error message when installation fails`() {
        val installer = mockk<InstallationService>()
        every { installer.install() } throws IllegalStateException("Installation failed: Could not download binary. Please try reinstalling using the installation script at https://fluxzero.io/docs/getting-started")

        val cmd = upgrade(installer)
        val result = cmd.test(emptyList())

        verify(exactly = 1) { installer.install() }
        assertTrue(result.output.contains("Installation failed: Could not download binary."), result.output)
        assertTrue(result.stdout.contains("Checking Fluxzero CLI installation..."))
        assertTrue(result.statusCode != 0)
    }
}
