package host.flux.templates.refactor

data class RefactorResult(
    val success: Boolean,
    val message: String,
    val warnings: List<String> = emptyList(),
    val operationsExecuted: Int = 0,
    val error: String? = null
) {
    companion object {
        fun success(message: String, operationsExecuted: Int = 0, warnings: List<String> = emptyList()) = 
            RefactorResult(true, message, warnings, operationsExecuted)
            
        fun failure(message: String, error: String? = null) = 
            RefactorResult(false, message, error = error)
            
        fun skipped(message: String) = 
            RefactorResult(true, message, operationsExecuted = 0)
    }
}