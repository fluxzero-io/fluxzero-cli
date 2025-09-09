package host.flux.templates.refactor

import host.flux.templates.models.BuildSystem
import kotlin.test.Test
import kotlin.test.assertEquals

class TemplateVariablesTest {
    
    @Test
    fun `should create template variables with all properties`() {
        val variables = TemplateVariables(
            packageName = "com.example.test",
            projectName = "my-project",
            groupId = "org.example",
            buildSystem = BuildSystem.MAVEN
        )
        
        assertEquals("com.example.test", variables.packageName)
        assertEquals("my-project", variables.projectName)
        assertEquals("org.example", variables.finalGroupId)
        assertEquals("com/example/test", variables.packagePath)
        assertEquals(BuildSystem.MAVEN, variables.buildSystem)
    }
    
    @Test
    fun `should use packageName as fallback for groupId when groupId is null`() {
        val variables = TemplateVariables(
            packageName = "com.example.test",
            projectName = "my-project",
            groupId = null
        )
        
        assertEquals("com.example.test", variables.finalGroupId)
    }
    
    @Test
    fun `should convert dots to slashes in packagePath`() {
        val variables = TemplateVariables(
            packageName = "com.example.deep.nested.package",
            projectName = "test-project"
        )
        
        assertEquals("com/example/deep/nested/package", variables.packagePath)
    }
    
    @Test
    fun `should handle single level package name`() {
        val variables = TemplateVariables(
            packageName = "app",
            projectName = "simple-app"
        )
        
        assertEquals("app", variables.packagePath)
        assertEquals("app", variables.finalGroupId)
    }
    
    @Test
    fun `companion create should work with defaults`() {
        val variables = TemplateVariables.create(
            projectName = "test-project"
        )
        
        assertEquals("com.example.app", variables.packageName)
        assertEquals("test-project", variables.projectName)
        assertEquals("com.example.app", variables.finalGroupId)
        assertEquals("com/example/app", variables.packagePath)
    }
    
    @Test
    fun `companion create should work with custom values`() {
        val variables = TemplateVariables.create(
            packageName = "org.test.custom",
            projectName = "custom-project",
            groupId = "io.custom",
            buildSystem = BuildSystem.GRADLE
        )
        
        assertEquals("org.test.custom", variables.packageName)
        assertEquals("custom-project", variables.projectName)
        assertEquals("io.custom", variables.finalGroupId)
        assertEquals("org/test/custom", variables.packagePath)
        assertEquals(BuildSystem.GRADLE, variables.buildSystem)
    }
    
    @Test
    fun `should handle null build system`() {
        val variables = TemplateVariables(
            packageName = "com.null.test",
            projectName = "null-test",
            buildSystem = null
        )
        
        assertEquals("com.null.test", variables.packageName)
        assertEquals("null-test", variables.projectName)
        assertEquals(null, variables.buildSystem)
    }
}