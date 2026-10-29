package host.flux.maven

import org.apache.maven.project.MavenProject
import org.apache.maven.artifact.DefaultArtifact
import org.apache.maven.artifact.handler.DefaultArtifactHandler
import org.apache.maven.model.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.jar.Attributes
import java.util.jar.JarOutputStream
import java.util.jar.Manifest

class PublishPackageMojoTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `standalone publish resolves the previously packaged Spring Boot application`() {
        val project = packagedProject()
        writeManifest(File(project.build.directory, "app.jar"), "org.springframework.boot.loader.launch.JarLauncher", "example.App")

        assertNull(project.artifact?.file)
        assertEquals("example.App", PublishPackageMojo().mainClassFromProjectArtifact(project))
    }

    @Test
    fun `assigned artifact retains precedence over the default package path`() {
        val project = packagedProject()
        writeManifest(File(project.build.directory, "app.jar"), "example.Stale")
        project.artifact = DefaultArtifact("example", "app", "1", "compile", "jar", null, DefaultArtifactHandler("jar")).apply {
            file = File(project.build.directory, "selected.jar").also { writeManifest(it, "example.Selected") }
        }

        assertEquals("example.Selected", PublishPackageMojo().mainClassFromProjectArtifact(project))
    }

    @Test
    fun `missing package does not select another jar in the output directory`() {
        val project = packagedProject()
        writeManifest(File(project.build.directory, "unrelated.jar"), "example.Other")

        assertNull(PublishPackageMojo().mainClassFromProjectArtifact(project))
    }

    private fun packagedProject() = MavenProject().apply {
        packaging = "jar"
        build = Build().apply {
            directory = temporaryFolder.newFolder().absolutePath
            finalName = "app"
        }
    }

    private fun writeManifest(file: File, mainClass: String, startClass: String? = null) {
        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            mainAttributes[Attributes.Name.MAIN_CLASS] = mainClass
            startClass?.let { mainAttributes.putValue("Start-Class", it) }
        }
        JarOutputStream(file.outputStream(), manifest).use { }
    }

    @Test
    fun `skips jar modules without a package name`() {
        val project = MavenProject().apply {
            groupId = "example"
            artifactId = "shared-library"
            packaging = "jar"
        }
        val mojo = PublishPackageMojo()
        PublishPackageMojo::class.java.getDeclaredField("project").apply {
            isAccessible = true
            set(mojo, project)
        }

        mojo.execute()
    }
}
