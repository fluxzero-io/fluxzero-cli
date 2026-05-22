package host.flux.desktop.services

import host.flux.desktop.model.GeneratedProject
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

class ProjectRegistryTest {
    @Test
    fun savesAndReplacesProjectsByPath() {
        val registryFile = createTempDirectory().resolve("projects.json")
        val registry = ProjectRegistry(registryFile)

        registry.saveProject(project(name = "first", sdkVersion = "1.0.0"))
        registry.saveProject(project(name = "renamed", sdkVersion = "1.1.0"))

        val projects = registry.listProjects()

        assertEquals(1, projects.size)
        assertEquals("renamed", projects.single().name)
        assertEquals("1.1.0", projects.single().sdkVersion)
    }

    private fun project(name: String, sdkVersion: String): GeneratedProject {
        return GeneratedProject(
            id = "id",
            name = name,
            path = "/tmp/example",
            template = "flux-basic-java",
            buildSystem = "maven",
            packageName = "com.example",
            generatedAt = "2026-05-22T10:00:00Z",
            cliVersion = "1.2.3",
            sdkVersion = sdkVersion,
            promptPath = "/tmp/example/START_PROMPT.md"
        )
    }
}
