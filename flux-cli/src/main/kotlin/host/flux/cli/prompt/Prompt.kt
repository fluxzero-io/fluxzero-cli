package host.flux.cli.prompt

interface Prompt {
    fun readLine(prompt: String): String
}