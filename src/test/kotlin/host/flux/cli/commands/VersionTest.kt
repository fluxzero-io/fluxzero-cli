package host.flux.cli.commands

import com.github.ajalt.clikt.testing.test
import host.flux.cli.Version
import kotlin.test.Test
import kotlin.test.assertEquals

class VersionTest {
    @Test
    fun `prints provided version`() {
        val cmd = Version(versionProvider = { "1.2.3" })
        val result = cmd.test(emptyList())
        assertEquals("1.2.3\n", result.stdout)
    }
}
