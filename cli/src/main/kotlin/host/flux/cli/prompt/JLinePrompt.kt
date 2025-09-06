package host.flux.cli.prompt

import host.flux.cli.prompt.Prompt
import org.jline.reader.LineReaderBuilder

class JLinePrompt : Prompt {
    private val reader = LineReaderBuilder.builder().build()
    override fun readLine(prompt: String): String = reader.readLine(prompt)
}