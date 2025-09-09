package host.flux.templates.refactor

data class OperationMessages(
    val warnings: MutableList<String> = mutableListOf(),
    val info: MutableList<String> = mutableListOf()
)
