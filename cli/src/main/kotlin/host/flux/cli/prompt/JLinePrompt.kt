package host.flux.cli.prompt

import org.jline.keymap.BindingReader
import org.jline.keymap.KeyMap
import org.jline.reader.EndOfFileException
import org.jline.reader.LineReaderBuilder
import org.jline.reader.UserInterruptException
import org.jline.terminal.Terminal
import org.jline.terminal.TerminalBuilder
import org.jline.utils.InfoCmp.Capability

class JLinePrompt(
    private val terminal: Terminal = TerminalBuilder.builder().system(true).build()
) : Prompt {
    private val reader = LineReaderBuilder.builder().terminal(terminal).build()

    override fun isInteractive(): Boolean = terminal.type != Terminal.TYPE_DUMB &&
        terminal.type != Terminal.TYPE_DUMB_COLOR

    override fun readLine(prompt: String): String = reader.readLine(prompt)

    override fun select(question: String, options: List<String>, defaultIndex: Int): Int {
        require(options.isNotEmpty()) { "At least one option is required" }
        require(defaultIndex in options.indices) { "Default option is out of range" }
        if (!supportsArrowSelection()) {
            return numberedSelection(question, options, defaultIndex)
        }
        return try {
            arrowSelection(question, options, defaultIndex)
        } catch (exception: EndOfFileException) {
            throw exception
        } catch (exception: UserInterruptException) {
            throw exception
        } catch (_: Exception) {
            numberedSelection(question, options, defaultIndex)
        }
    }

    private fun supportsArrowSelection(): Boolean {
        if (terminal.type == Terminal.TYPE_DUMB || terminal.type == Terminal.TYPE_DUMB_COLOR) {
            return false
        }
        return KeyMap.key(terminal, Capability.key_up) != null &&
                KeyMap.key(terminal, Capability.key_down) != null
    }

    private fun arrowSelection(question: String, options: List<String>, defaultIndex: Int): Int {
        val originalAttributes = terminal.enterRawMode()
        try {
            val bindings = selectionBindings(options.size)
            val bindingReader = BindingReader(terminal.reader())
            var selected = defaultIndex
            renderSelection(question, options, selected, redraw = false)
            while (true) {
                when (val binding = bindingReader.readBinding(bindings)) {
                    MOVE_UP -> selected = (selected - 1 + options.size) % options.size
                    MOVE_DOWN -> selected = (selected + 1) % options.size
                    ACCEPT -> return selected
                    INTERRUPT -> throw UserInterruptException("")
                    null -> throw EndOfFileException()
                    IGNORE -> continue
                    else -> if (binding in options.indices) {
                        selected = binding
                        renderSelection(question, options, selected, redraw = true)
                        return selected
                    }
                }
                renderSelection(question, options, selected, redraw = true)
            }
        } finally {
            terminal.setAttributes(originalAttributes)
        }
    }

    private fun selectionBindings(optionCount: Int): KeyMap<Int> = KeyMap<Int>().apply {
        bind(MOVE_UP, KeyMap.key(terminal, Capability.key_up), "\u001b[A")
        bind(MOVE_DOWN, KeyMap.key(terminal, Capability.key_down), "\u001b[B")
        bind(ACCEPT, "\r", "\n")
        bind(INTERRUPT, KeyMap.ctrl('C'), KeyMap.esc())
        setNomatch(IGNORE)
        (0 until minOf(optionCount, 9)).forEach { index -> bind(index, (index + 1).toString()) }
    }

    private fun renderSelection(
        question: String,
        options: List<String>,
        selected: Int,
        redraw: Boolean
    ) {
        val output = terminal.writer()
        if (redraw) {
            output.print("\u001b[${options.size}A")
        } else {
            output.println(question)
            output.println()
        }
        options.forEachIndexed { index, option ->
            if (redraw) {
                output.print("\r\u001b[2K")
            }
            if (index == selected) {
                output.println("\u001b[36m› $option\u001b[0m")
            } else {
                output.println("  $option")
            }
        }
        output.flush()
    }

    private fun numberedSelection(question: String, options: List<String>, defaultIndex: Int): Int {
        val output = terminal.writer()
        output.println(question)
        output.println()
        options.forEachIndexed { index, option -> output.println("${index + 1}) $option") }
        output.flush()
        while (true) {
            val answer = readLine("Choice [1-${options.size}, default ${defaultIndex + 1}]: ").trim()
            if (answer.isEmpty()) {
                return defaultIndex
            }
            val selected = answer.toIntOrNull()?.minus(1)
            if (selected != null && selected in options.indices) {
                return selected
            }
            output.println("Choose a number between 1 and ${options.size}.")
            output.flush()
        }
    }

    private companion object {
        const val MOVE_UP = -1
        const val MOVE_DOWN = -2
        const val ACCEPT = -3
        const val INTERRUPT = -4
        const val IGNORE = -5
    }
}
