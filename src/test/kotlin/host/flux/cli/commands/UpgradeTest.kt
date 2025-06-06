package host.flux.cli.commands

import com.github.ajalt.clikt.testing.test
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertTrue

class UpgradeTest {
    @Test
    fun `runs installer and prints version`() {
        val installer = mockk<Installer>()
        every { installer.installLatest() } returns "v1.2.3"

        val cmd = Upgrade(installer)
        val result = cmd.test(emptyList())

        verify(exactly = 1) { installer.installLatest() }
        assertTrue(result.stdout.contains("v1.2.3"))
    }
}
