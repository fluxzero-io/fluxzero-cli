package host.flux.cli

import java.net.ConnectException
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
