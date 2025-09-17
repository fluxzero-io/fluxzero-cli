package host.flux.templates.refactor

import java.nio.file.Path

sealed class RefactorOperation {
    abstract fun execute(templateRoot: Path, variables: TemplateVariables): OperationMessages
}

data class ReplaceOperation(
    val files: List<String>,
    val find: String,
    val replace: String,
    val regex: Boolean = false
) : RefactorOperation() {
    override fun execute(templateRoot: Path, variables: TemplateVariables): OperationMessages {
        val messages = OperationMessages()
        val finalReplace = expandVariables(replace, variables)
        val finalFind = if (regex) expandVariables(find, variables) else find
        
        FileOperationHelper.findMatchingFiles(templateRoot, files).forEach { file ->
            FileOperationHelper.replaceInFile(file, finalFind, finalReplace, regex, messages)
        }
        return messages
    }
}

data class DeleteOperation(
    val files: List<String>
) : RefactorOperation() {
    override fun execute(templateRoot: Path, variables: TemplateVariables): OperationMessages {
        val messages = OperationMessages()
        FileOperationHelper.findMatchingFiles(templateRoot, files).forEach { file ->
            FileOperationHelper.deleteFile(file, messages)
        }
        return messages
    }
}

data class RenameOperation(
    val from: String,
    val to: String
) : RefactorOperation() {
    override fun execute(templateRoot: Path, variables: TemplateVariables): OperationMessages {
        val messages = OperationMessages()
        val finalFrom = expandVariables(from, variables)
        val finalTo = expandVariables(to, variables)
        
        val sourcePath = templateRoot.resolve(finalFrom)
        val targetPath = templateRoot.resolve(finalTo)
        
        FileOperationHelper.moveFile(sourcePath, targetPath, messages)
        return messages
    }
}

data class CreateDirectoryOperation(
    val directory: String
) : RefactorOperation() {
    override fun execute(templateRoot: Path, variables: TemplateVariables): OperationMessages {
        val messages = OperationMessages()
        val finalDirectory = expandVariables(directory, variables)
        val targetPath = templateRoot.resolve(finalDirectory)
        FileOperationHelper.createDirectory(targetPath, messages)
        return messages
    }
}

data class CleanupEmptyDirectoriesOperation(
    val paths: List<String> = listOf("src/main", "src/test")
) : RefactorOperation() {
    override fun execute(templateRoot: Path, variables: TemplateVariables): OperationMessages {
        val messages = OperationMessages()
        paths.forEach { path ->
            val targetPath = templateRoot.resolve(path)
            FileOperationHelper.cleanupEmptyDirectories(targetPath, messages)
        }
        return messages
    }
}

data class ChmodOperation(
    val files: List<String>,
    val mode: String
) : RefactorOperation() {
    override fun execute(templateRoot: Path, variables: TemplateVariables): OperationMessages {
        val messages = OperationMessages()
        val matchingFiles = FileOperationHelper.findMatchingFiles(templateRoot, files)
        FileOperationHelper.chmodFiles(matchingFiles, mode, messages)
        return messages
    }
}

private fun expandVariables(template: String, variables: TemplateVariables): String {
    var result = template
    
    // Direct property access - more efficient and type-safe
    result = result.replace("\${package}", variables.packageName)
    result = result.replace("{{package}}", variables.packageName)
    result = result.replace("\${packagePath}", variables.packagePath)
    result = result.replace("{{packagePath}}", variables.packagePath)
    result = result.replace("\${projectName}", variables.projectName)
    result = result.replace("{{projectName}}", variables.projectName)
    result = result.replace("\${groupId}", variables.finalGroupId)
    result = result.replace("{{groupId}}", variables.finalGroupId)
    result = result.replace("\${artifactId}", variables.finalArtifactId)
    result = result.replace("{{artifactId}}", variables.finalArtifactId)
    result = result.replace("\${description}", variables.finalDescription)
    result = result.replace("{{description}}", variables.finalDescription)
    
    return result
}