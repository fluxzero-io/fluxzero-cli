package host.flux.templates.refactor

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import host.flux.templates.models.BuildSystem
import java.nio.file.Files
import java.nio.file.Path

class TemplateRefactor {
    
    private val yamlMapper = ObjectMapper(YAMLFactory()).registerKotlinModule()
    
    fun refactorTemplate(
        templateRoot: Path,
        variables: TemplateVariables
    ): RefactorResult {
        val refactorConfigFile = templateRoot.resolve("refactor.yaml")
        
        if (!Files.exists(refactorConfigFile)) {
            return RefactorResult.skipped("No refactor.yaml found in template, skipping refactoring")
        }
        
        return try {
            val configContent = Files.readString(refactorConfigFile)
            val config = parseConfig(configContent)
            
            val allWarnings = mutableListOf<String>()
            val allInfo = mutableListOf<String>()
            
            config.operations.forEach { operationConfig ->
                val operation = operationConfig.toOperation()
                val messages = operation.execute(templateRoot, variables)
                allWarnings.addAll(messages.warnings)
                allInfo.addAll(messages.info)
            }
            
            // Delete unused build files based on build system selection
            val buildSystemOperations = getBuildSystemDeleteOperations(variables)
            buildSystemOperations.forEach { operation ->
                val messages = operation.execute(templateRoot, variables)
                allWarnings.addAll(messages.warnings)
                allInfo.addAll(messages.info)
            }
            
            // Always run cleanup of empty directories after all other operations
            val cleanupOperation = CleanupEmptyDirectoriesOperation()
            val cleanupMessages = cleanupOperation.execute(templateRoot, variables)
            allWarnings.addAll(cleanupMessages.warnings)
            allInfo.addAll(cleanupMessages.info)
            
            // Clean up the refactor.yaml file after processing
            Files.deleteIfExists(refactorConfigFile)
            
            val totalOperations = config.operations.size + buildSystemOperations.size + 1 // +1 for cleanup
            RefactorResult.success(
                "Successfully applied template refactoring", 
                operationsExecuted = totalOperations,
                warnings = allWarnings
            )
            
        } catch (e: Exception) {
            RefactorResult.failure(
                "Failed to apply template refactoring: ${e.message}",
                error = e.message
            )
        }
    }
    
    private fun parseConfig(yamlContent: String): RefactorConfig {
        // Parse the YAML content into a generic structure first
        val rawConfig = yamlMapper.readValue(yamlContent, Map::class.java)
        val operations = rawConfig["operations"] as? List<Map<String, Any>> ?: emptyList()
        
        val operationConfigs = operations.mapNotNull { operationMap ->
            when (operationMap["type"] as? String) {
                "replace" -> ReplaceConfig(
                    files = (operationMap["files"] as? List<String>) ?: emptyList(),
                    find = operationMap["find"] as? String ?: "",
                    replace = operationMap["replace"] as? String ?: "",
                    regex = operationMap["regex"] as? Boolean ?: false
                )
                "delete" -> DeleteConfig(
                    files = (operationMap["files"] as? List<String>) ?: emptyList()
                )
                "rename" -> RenameConfig(
                    from = operationMap["from"] as? String ?: "",
                    to = operationMap["to"] as? String ?: ""
                )
                "createDirectory" -> CreateDirectoryConfig(
                    directory = operationMap["directory"] as? String ?: ""
                )
                "cleanupEmptyDirectories" -> CleanupEmptyDirectoriesConfig(
                    paths = (operationMap["paths"] as? List<String>) ?: listOf("src/main", "src/test")
                )
                else -> {
                    println("Unknown operation type: ${operationMap["type"]}")
                    null
                }
            }
        }
        
        return RefactorConfig(operations = operationConfigs)
    }
    
    private fun getBuildSystemDeleteOperations(variables: TemplateVariables): List<DeleteOperation> {
        return when (variables.buildSystem) {
            BuildSystem.MAVEN -> {
                // Delete Gradle files when Maven is chosen
                listOf(
                    DeleteOperation(listOf(
                        "build.gradle.kts",
                        "settings.gradle.kts", 
                        "gradle.properties",
                        "gradlew",
                        "gradlew.bat",
                        "gradle"
                    ))
                )
            }
            BuildSystem.GRADLE -> {
                // Delete Maven files when Gradle is chosen
                listOf(
                    DeleteOperation(listOf("pom.xml")),
                    DeleteOperation(listOf(".mvn"))
                )
            }
            null -> {
                // No build system specified, don't delete anything
                emptyList()
            }
        }
    }
}