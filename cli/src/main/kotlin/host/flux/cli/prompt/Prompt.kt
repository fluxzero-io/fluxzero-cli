package host.flux.cli.prompt

interface Prompt {
    fun readLine(prompt: String): String

    fun select(question: String, options: List<String>, defaultIndex: Int = 0): Int
}
