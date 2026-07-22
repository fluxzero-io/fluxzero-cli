package host.flux.maven

import org.apache.maven.project.MavenProject
import org.junit.Test

class PublishPackageMojoTest {
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
