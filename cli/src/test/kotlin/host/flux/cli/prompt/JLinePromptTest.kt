package host.flux.cli.prompt

import org.jline.terminal.impl.DumbTerminal
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.charset.StandardCharsets
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JLinePromptTest {
    @Test
    fun `selects an option using arrow keys in an interactive terminal`() {
        val input = PipedInputStream()
        val keyboard = PipedOutputStream(input)
        val output = ByteArrayOutputStream()

        testTerminal(input, output, "xterm").use { terminal ->
            val inputThread = thread {
                keyboard.write("\u001b[B\r".toByteArray())
                keyboard.flush()
            }
            val selected = JLinePrompt(terminal).select("Choose:", listOf("First", "Second"))

            assertEquals(1, selected)
            inputThread.join()
            keyboard.close()
        }
        val rendered = output.toString()
        assertTrue(rendered.contains("› First"))
        assertTrue(rendered.contains("› Second"))
    }

    @Test
    fun `falls back to numbered selection for a dumb terminal`() {
        val input = PipedInputStream()
        val keyboard = PipedOutputStream(input)
        val output = ByteArrayOutputStream()

        testTerminal(input, output, "dumb").use { terminal ->
            val inputThread = thread {
                keyboard.write("2\n".toByteArray())
                keyboard.flush()
            }
            val selected = JLinePrompt(terminal).select("Choose:", listOf("First", "Second"))

            assertEquals(1, selected)
            inputThread.join()
            keyboard.close()
        }
        val rendered = output.toString()
        assertTrue(rendered.contains("1) First"))
        assertTrue(rendered.contains("2) Second"))
        assertTrue(rendered.contains("Choice [1-2, default 1]:"))
    }

    private fun testTerminal(input: java.io.InputStream, output: java.io.OutputStream, type: String) =
        DumbTerminal("test", type, input, output, StandardCharsets.UTF_8)
}
