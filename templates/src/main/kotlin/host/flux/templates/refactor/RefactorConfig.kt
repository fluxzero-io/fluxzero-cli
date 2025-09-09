package host.flux.templates.refactor

data class RefactorConfig(
    val operations: List<OperationConfig>
)

sealed class OperationConfig {
    abstract fun toOperation(): RefactorOperation
}

data class ReplaceConfig(
    val type: String = "replace",
    val files: List<String>,
    val find: String,
    val replace: String,
    val regex: Boolean = false
) : OperationConfig() {
    override fun toOperation(): RefactorOperation = ReplaceOperation(files, find, replace, regex)
}

data class DeleteConfig(
    val type: String = "delete",
    val files: List<String>
) : OperationConfig() {
    override fun toOperation(): RefactorOperation = DeleteOperation(files)
}

data class RenameConfig(
    val type: String = "rename",
    val from: String,
    val to: String
) : OperationConfig() {
    override fun toOperation(): RefactorOperation = RenameOperation(from, to)
}

data class CreateDirectoryConfig(
    val type: String = "createDirectory",
    val directory: String
) : OperationConfig() {
    override fun toOperation(): RefactorOperation = CreateDirectoryOperation(directory)
}

data class CleanupEmptyDirectoriesConfig(
    val type: String = "cleanupEmptyDirectories",
    val paths: List<String> = listOf("src/main", "src/test")
) : OperationConfig() {
    override fun toOperation(): RefactorOperation = CleanupEmptyDirectoriesOperation(paths)
}