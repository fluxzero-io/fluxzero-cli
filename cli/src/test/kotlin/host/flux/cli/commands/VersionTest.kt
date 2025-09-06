package host.flux.cli.commands

import com.github.ajalt.clikt.testing.test
import host.flux.cli.commands.Version
import host.flux.cli.services.VersionService
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals

class VersionTest {
    @Test
    fun `prints provided version`() {
        val versionService = mockk<VersionService>()
        every { versionService.getCurrentVersion() } returns "1.2.3"
        
        val cmd = Version(versionService)
        val result = cmd.test(emptyList())
        assertEquals("1.2.3\n", result.stdout)
    }

}
