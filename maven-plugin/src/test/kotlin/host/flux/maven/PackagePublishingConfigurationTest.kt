package host.flux.maven

import host.flux.publishing.PackageNameSupport
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import org.apache.maven.plugin.MojoFailureException
import org.apache.maven.model.Scm
import org.apache.maven.project.MavenProject
import org.codehaus.plexus.component.configurator.expression.ExpressionEvaluator
import org.codehaus.plexus.component.configurator.converters.composite.ObjectWithFieldsConverter
import org.codehaus.plexus.component.configurator.converters.lookup.DefaultConverterLookup
import org.codehaus.plexus.configuration.xml.XmlPlexusConfiguration
import org.junit.Test

class PackagePublishingConfigurationTest {
    @Test
    fun `defaults to linux amd64 and resolves configured platforms`() {
        assertEquals(
            listOf(host.flux.publishing.JavaPackagePlatform("linux", "amd64")),
            PackagePublishingConfigurationSupport.platforms(emptyList())
        )
        assertEquals(
            listOf(
                host.flux.publishing.JavaPackagePlatform("linux", "amd64"),
                host.flux.publishing.JavaPackagePlatform("linux", "arm64")
            ),
            PackagePublishingConfigurationSupport.platforms(
                listOf(
                    Platform().apply {
                        os = "LINUX"
                        architecture = "AMD64"
                    },
                    Platform().apply {
                        os = "linux"
                        architecture = "arm64"
                    }
                )
            )
        )
    }

    @Test
    fun `resolves relative extra directory sources from the module directory`() {
        val projectDirectory = Files.createTempDirectory("fluxzero-project")

        val result = PackagePublishingConfigurationSupport.extraDirectories(
            listOf(ExtraDirectory().apply {
                from = "config"
                into = "/app/config"
            }),
            projectDirectory
        )

        assertEquals(projectDirectory.resolve("config"), result.single().source)
        assertEquals("/app/config", result.single().normalizedContainerPath)
    }

    @Test
    fun `keeps null label values as removals and rejects duplicate custom keys`() {
        val configuredLabels = listOf(
            Label().apply {
                key = "org.opencontainers.image.source"
                value = "https://example.com/source"
            },
            Label().apply {
                key = "org.opencontainers.image.revision"
            }
        )

        val labels = PackagePublishingConfigurationSupport.labels(false, configuredLabels)

        assertEquals(false, labels.includeDefaults)
        assertEquals("https://example.com/source", labels.overrides["org.opencontainers.image.source"])
        assertNull(labels.overrides["org.opencontainers.image.revision"])

        val duplicate = listOf(
            Label().apply { key = "example.label" },
            Label().apply {
                key = "example.label"
                value = "second"
            }
        )
        assertFailsWith<MojoFailureException> {
            PackagePublishingConfigurationSupport.labels(true, duplicate)
        }
    }

    @Test
    fun `derives vendor neutral default labels from Maven and Git metadata`() {
        val project = MavenProject().apply {
            groupId = "io.example"
            artifactId = "service"
            version = "1.2.3"
            url = "https://example.org/service"
            description = "Example service"
            scm = Scm().apply { url = "https://code.example.org/team/service.git" }
        }
        val gitInfo = PackageNameSupport.GitInfo(
            branch = "main",
            shortSha = "0123456789ab",
            dirty = false,
            sha = "0123456789abcdef0123456789abcdef01234567",
            remoteUrl = "https://fallback.example.org/team/service"
        )

        assertEquals(
            mapOf(
                "io.fluxzero.maven.group-id" to "io.example",
                "io.fluxzero.maven.artifact-id" to "service",
                "io.fluxzero.maven.version" to "1.2.3",
                "org.opencontainers.image.revision" to "0123456789abcdef0123456789abcdef01234567",
                "org.opencontainers.image.source" to "https://code.example.org/team/service",
                "org.opencontainers.image.url" to "https://example.org/service",
                "org.opencontainers.image.description" to "Example service"
            ),
            MavenPackageLabels.defaults(project, gitInfo)
        )
    }

    @Test
    fun `plexus binds platforms extra directories and mixed label settings`() {
        val mojo = PublishPackageMojo()
        val configuration = XmlPlexusConfiguration("configuration").apply {
            addChild(XmlPlexusConfiguration("platforms").apply {
                addChild(platform("linux", "amd64"))
                addChild(platform("linux", "arm64"))
            })
            addChild(XmlPlexusConfiguration("extraDirectories").apply {
                addChild(XmlPlexusConfiguration("extraDirectory").apply {
                    addChild(XmlPlexusConfiguration("from").apply { value = "config" })
                    addChild(XmlPlexusConfiguration("into").apply { value = "/app/config" })
                })
            })
            addChild(XmlPlexusConfiguration("includeDefaultLabels").apply { value = "false" })
            addChild(XmlPlexusConfiguration("labels").apply {
                addChild(XmlPlexusConfiguration("label").apply {
                    addChild(XmlPlexusConfiguration("key").apply { value = "org.opencontainers.image.source" })
                    addChild(XmlPlexusConfiguration("value").apply { value = "https://example.com/source" })
                })
                addChild(XmlPlexusConfiguration("label").apply {
                    addChild(XmlPlexusConfiguration("key").apply { value = "org.opencontainers.image.revision" })
                })
            })
        }

        ObjectWithFieldsConverter().processConfiguration(
            DefaultConverterLookup(),
            mojo,
            javaClass.classLoader,
            configuration,
            object : ExpressionEvaluator {
                override fun evaluate(expression: String): Any = expression
                override fun alignToBaseDirectory(path: File): File = path
            }
        )

        val platforms = field<List<Platform>>(mojo, "platforms")
        val extraDirectories = field<List<ExtraDirectory>>(mojo, "extraDirectories")
        val includeDefaultLabels = field<Boolean>(mojo, "includeDefaultLabels")
        val labels = field<List<Label>>(mojo, "labels")
        assertEquals(listOf("amd64", "arm64"), platforms.map { it.architecture })
        assertEquals("config", extraDirectories.single().from)
        assertEquals(false, includeDefaultLabels)
        assertEquals(2, labels.size)
        assertEquals("https://example.com/source", labels[0].value)
        assertNull(labels[1].value)
    }

    private fun platform(os: String, architecture: String): XmlPlexusConfiguration =
        XmlPlexusConfiguration("platform").apply {
            addChild(XmlPlexusConfiguration("os").apply { value = os })
            addChild(XmlPlexusConfiguration("architecture").apply { value = architecture })
        }

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(instance: Any, name: String): T =
        instance::class.java.getDeclaredField(name).apply { isAccessible = true }.get(instance) as T
}
