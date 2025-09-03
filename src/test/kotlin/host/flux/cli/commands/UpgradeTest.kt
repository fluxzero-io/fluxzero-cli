package host.flux.cli.commands

import com.github.ajalt.clikt.testing.test
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import host.flux.cli.install.InstallResult
import host.flux.cli.install.Installer
import kotlin.test.Test
import kotlin.test.assertTrue

class UpgradeTest {
    @Test
    fun `upgrades when newer version is available`() {
        val installer = mockk<Installer>()
        every { installer.install() } returns InstallResult.Upgraded("v1.0.0", "v1.2.3")

        val cmd = Upgrade(installer)
        val result = cmd.test(emptyList())

        verify(exactly = 1) { installer.install() }
        assertTrue(result.stdout.contains("fluxzero-cli upgraded from v1.0.0 to v1.2.3"))
    }

    @Test
    fun `shows already up to date message when no upgrade needed`() {
        val installer = mockk<Installer>()
        every { installer.install() } returns InstallResult.AlreadyLatest("v1.2.3")

        val cmd = Upgrade(installer)
        val result = cmd.test(emptyList())

        verify(exactly = 1) { installer.install() }
        assertTrue(result.stdout.contains("fluxzero-cli is already up to date (current version: v1.2.3)"))
    }

    @Test
    fun `shows fresh install message for new installation`() {
        val installer = mockk<Installer>()
        every { installer.install() } returns InstallResult.FreshInstall("v1.2.3")

        val cmd = Upgrade(installer)
        val result = cmd.test(emptyList())

        verify(exactly = 1) { installer.install() }
        assertTrue(result.stdout.contains("fluxzero-cli installed (version: v1.2.3)"))
    }

    @Test
    fun `shows clean error message when installation fails`() {
        val installer = mockk<Installer>()
        every { installer.install() } throws IllegalStateException("Installation failed: Could not download binary. Please try reinstalling using the installation script at https://fluxzero.io/docs/getting-started")

        val cmd = Upgrade(installer)
        val result = cmd.test(emptyList())

        verify(exactly = 1) { installer.install() }
        assertTrue(result.stderr.contains("Error: Installation failed: Could not download binary. Please try reinstalling using the installation script at https://fluxzero.io/docs/getting-started"))
        assertTrue(result.stdout.isEmpty())
    }
}
