package host.flux.dev

import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DevServerUpdatesTest {
    @Test fun `pinned and development versions do not fetch metadata`() {
        val updates = DevServerUpdates(latest = { error("must not check") })
        assertEquals("pinned", updates.check("1.2.0", true)["status"])
        assertEquals("pinned", updates.check("1-SNAPSHOT")["status"])
        assertEquals("pinned", updates.check("2.0.0")["status"])
    }
    @Test fun `numeric comparison never offers a downgrade`() {
        val updates = DevServerUpdates(latest = { "1.9.0" })
        assertEquals("current", updates.check("1.10.0")["status"])
        assertEquals("current", updates.check("1.9.0")["status"])
        assertEquals("available", updates.check("1.8.0")["status"])
    }
    @Test fun `prepare verifies the selected version before downloading`() {
        var downloads = 0
        val updates = DevServerUpdates(latest = { "1.10.0" }, artifact = { downloads++; Path.of("server.jar") })
        assertFailsWith<IllegalArgumentException> { updates.prepare("1.9.0", "1.9.9") }
        assertFailsWith<IllegalArgumentException> { updates.prepare("1.9.0", "1.10.0", true) }
        assertEquals(0, downloads)
        assertEquals(Path.of("server.jar").toAbsolutePath().toString(), updates.prepare("1.9.0", "1.10.0")["artifact"])
        assertEquals(1, downloads)
    }
}
