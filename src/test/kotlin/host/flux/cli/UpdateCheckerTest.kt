import host.flux.cli.UpdateChecker
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpdateCheckerTest {
    @Test
    fun `isNewer returns true for newer version`() {
        assertTrue(UpdateChecker.isNewer("1.0.0", "1.0.1"))
        assertTrue(UpdateChecker.isNewer("1.0.0", "2.0.0"))
        assertTrue(UpdateChecker.isNewer("1.2.3", "1.3.0"))
    }

    @Test
    fun `isNewer returns false when not newer`() {
        assertFalse(UpdateChecker.isNewer("1.0.0", "1.0.0"))
        assertFalse(UpdateChecker.isNewer("1.0.0", "0.9.9"))
    }
}
