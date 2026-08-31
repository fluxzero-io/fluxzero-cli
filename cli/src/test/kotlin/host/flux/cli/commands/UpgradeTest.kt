package host.flux.cli.commands

import com.github.ajalt.clikt.testing.test
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import host.flux.cli.services.InstallResult
import host.flux.cli.services.InstallationService
import kotlin.test.Test
import kotlin.test.assertTrue

class UpgradeTest {
    @Test
    fun `upgrades when newer version is available`() {
        val installer = mockk<InstallationService>()
        every { installer.install() } returns InstallResult.Upgraded("v1.0.0", "v1.2.3")

        val cmd = Upgrade(installer)
        val result = cmd.test(emptyList())

        verify(exactly = 1) { installer.install() }
        assertTrue(result.stdout.contains("Checking Fluxzero CLI installation..."))
        assertTrue(result.stdout.contains("✅ Fluxzero CLI upgraded from v1.0.0 to v1.2.3"))
    }

    @Test
    fun `shows already up to date message when no upgrade needed`() {
        val installer = mockk<InstallationService>()
        every { installer.install() } returns InstallResult.AlreadyLatest("v1.2.3")

        val cmd = Upgrade(installer)
        val result = cmd.test(emptyList())

        verify(exactly = 1) { installer.install() }
        assertTrue(result.stdout.contains("Checking Fluxzero CLI installation..."))
        assertTrue(result.stdout.contains("Fluxzero CLI is already up to date (current version: v1.2.3)"))
    }

    @Test
    fun `shows fresh install message for new installation`() {
        val installer = mockk<InstallationService>()
        every { installer.install() } returns InstallResult.FreshInstall("v1.2.3")

        val cmd = Upgrade(installer)
        val result = cmd.test(emptyList())

        verify(exactly = 1) { installer.install() }
        assertTrue(result.stdout.contains("Checking Fluxzero CLI installation..."))
        assertTrue(result.stdout.contains("✅ Fluxzero CLI installed (version: v1.2.3)"))
    }

    @Test
    fun `redirects externally managed installation to its package manager`() {
        val installer = mockk<InstallationService>()
        every { installer.install() } returns InstallResult.ExternallyManaged(
            "Homebrew", "brew update && brew upgrade fluxzero"
        )

        val result = Upgrade(installer).test(emptyList())

        verify(exactly = 1) { installer.install() }
        assertTrue(result.stdout.contains("This Fluxzero CLI installation is managed by Homebrew."))
        assertTrue(result.stdout.contains("Upgrade with: brew update && brew upgrade fluxzero"))
    }

    @Test
    fun `shows clean error message when installation fails`() {
        val installer = mockk<InstallationService>()
        every { installer.install() } throws IllegalStateException("Installation failed: Could not download binary. Please try reinstalling using the installation script at https://fluxzero.io/docs/getting-started")

        val cmd = Upgrade(installer)
        val result = cmd.test(emptyList())

        verify(exactly = 1) { installer.install() }
        assertTrue(result.stderr.contains("Error: Installation failed: Could not download binary. Please try reinstalling using the installation script at https://fluxzero.io/docs/getting-started"))
        assertTrue(result.stdout.contains("Checking Fluxzero CLI installation..."))
    }
}
