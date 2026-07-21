package host.flux.maven

import host.flux.publishing.JavaPackageRegistryCredential
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.apache.maven.plugin.MojoFailureException
import org.codehaus.plexus.component.configurator.expression.ExpressionEvaluator
import org.codehaus.plexus.component.configurator.converters.composite.ObjectWithFieldsConverter
import org.codehaus.plexus.component.configurator.converters.lookup.DefaultConverterLookup
import org.codehaus.plexus.configuration.xml.XmlPlexusConfiguration
import org.junit.Test

class RegistryAuthenticationSupportTest {
    @Test
    fun `keeps explicit images without resolving registry identity`() {
        var identityRequests = 0
        assertEquals(
            listOf("registry.example.com/team/service"),
            PackageImageSupport.resolve(
                configuredImages = listOf(" ", " registry.example.com/team/service "),
                packageName = "service",
                credentials = emptyList(),
                organisationId = {
                    identityRequests++
                    "unused"
                }
            )
        )
        assertEquals(0, identityRequests)
    }

    @Test
    fun `requires at least one explicit image`() {
        val missingTarget = assertFailsWith<MojoFailureException> {
            PackageImageSupport.resolve(emptyList(), "service", emptyList()) { "unused" }
        }
        assertEquals("Configure at least one <image> under <images>.", missingTarget.message)
    }

    @Test
    fun `resolves organisation placeholder using exact registry host authentication`() {
        val fluxzero = JavaPackageRegistryCredential("registry.fluxzero.io", password = "fluxzero-token")
        val github = JavaPackageRegistryCredential("ghcr.io", password = "github-token")
        val resolvedFor = mutableListOf<String>()

        val images = PackageImageSupport.resolve(
            listOf(
                "registry.fluxzero.io/\${organisationId}/\${packageName}",
                "ghcr.io/fluxzero/\${packageName}"
            ),
            "service",
            listOf(fluxzero, github)
        ) {
            resolvedFor += it.host
            "owned"
        }

        assertEquals(
            listOf("registry.fluxzero.io/owned/service", "ghcr.io/fluxzero/service"),
            images
        )
        assertEquals(listOf("registry.fluxzero.io"), resolvedFor)
    }

    @Test
    fun `requires organisation placeholder to be a complete segment with matching authentication`() {
        listOf(
            "registry.fluxzero.io/prefix-\${organisationId}/service",
            "registry.fluxzero.io/\${organisationId}/\${organisationId}/service",
            "\${organisationId}/service"
        ).forEach { image ->
            val invalid = assertFailsWith<MojoFailureException> {
                PackageImageSupport.resolve(
                    listOf(image),
                    "service",
                    listOf(JavaPackageRegistryCredential("registry.fluxzero.io", password = "token"))
                ) { "owned" }
            }
            assertTrue(invalid.message.orEmpty().contains("complete image path segment"))
        }

        val missingAuthentication = assertFailsWith<MojoFailureException> {
            PackageImageSupport.resolve(
                listOf("registry.fluxzero.io/\${organisationId}/service"),
                "service",
                emptyList()
            ) { "owned" }
        }
        assertTrue(missingAuthentication.message.orEmpty().contains("has no authentication"))
    }

    @Test
    fun `rejects invalid organisation id returned by registry identity`() {
        val invalid = assertFailsWith<MojoFailureException> {
            PackageImageSupport.resolve(
                listOf("registry.fluxzero.io/\${organisationId}/service"),
                "service",
                listOf(JavaPackageRegistryCredential("registry.fluxzero.io", password = "token"))
            ) { "not/a/path-segment" }
        }

        assertEquals("Registry identity returned an invalid organisationId.", invalid.message)
    }

    @Test
    fun `requires package name placeholder to be a complete path segment`() {
        listOf(
            "registry.fluxzero.io/owned/prefix-\${packageName}",
            "registry.fluxzero.io/owned/\${packageName}/\${packageName}",
            "\${packageName}/owned/service"
        ).forEach { image ->
            val invalid = assertFailsWith<MojoFailureException> {
                PackageImageSupport.resolve(listOf(image), "service", emptyList()) { "unused" }
            }
            assertTrue(invalid.message.orEmpty().contains("complete image path segment"))
        }
    }

    @Test
    fun `resolves basic authentication from its username and token`() {
        val credential = RegistryAuthenticationSupport.resolve(
            authentications = listOf(
                basicAuthentication(
                    host = "ghcr.io",
                    username = "registry-user",
                    token = "registry-token"
                )
            ),
            githubToken = { error("GitHub OIDC must not be requested for basic authentication") }
        ).single()

        assertEquals("ghcr.io", credential.host)
        assertEquals("registry-user", credential.username)
        assertEquals("registry-token", credential.password)
    }

    @Test
    fun `defaults basic username to empty`() {
        val credential = RegistryAuthenticationSupport.resolve(
            authentications = listOf(basicAuthentication(token = "registry-token")),
            githubToken = { error("GitHub OIDC must not be requested for basic authentication") }
        ).single()

        assertEquals("", credential.username)
    }

    @Test
    fun `resolves github oidc authentication with configured username and audience`() {
        var requestedAudience: String? = null
        val credential = RegistryAuthenticationSupport.resolve(
            authentications = listOf(
                githubOidcAuthentication(
                    username = "custom-github-user",
                    audience = "https://cloud.fluxzero.io/registry"
                )
            ),
            githubToken = {
                requestedAudience = it
                "github-oidc-token"
            }
        ).single()

        assertEquals("https://cloud.fluxzero.io/registry", requestedAudience)
        assertEquals("custom-github-user", credential.username)
        assertEquals("github-oidc-token", credential.password)
    }

    @Test
    fun `defaults github oidc username to empty`() {
        val credential = RegistryAuthenticationSupport.resolve(
            authentications = listOf(githubOidcAuthentication()),
            githubToken = { "github-oidc-token" }
        ).single()

        assertEquals("", credential.username)
    }

    @Test
    fun `allows anonymous access but requires explicit hosts for configured authentication`() {
        assertTrue(
            RegistryAuthenticationSupport.resolve(emptyList()) {
                error("GitHub OIDC must not be requested for anonymous access")
            }.isEmpty()
        )

        val missingHost = assertFailsWith<IllegalArgumentException> {
            RegistryAuthenticationSupport.resolve(
                listOf(
                    Authentication().apply {
                        basic = BasicAuthenticationConfiguration().apply { token = "registry-token" }
                    }
                )
            ) { "unused" }
        }
        assertEquals("Missing <host> for registry authentication 1.", missingHost.message)
    }

    @Test
    fun `requires github oidc audience before requesting a token`() {
        var tokenRequested = false
        val exception = assertFailsWith<IllegalArgumentException> {
            RegistryAuthenticationSupport.resolve(
                authentications = listOf(githubOidcAuthentication(audience = null)),
                githubToken = {
                    tokenRequested = true
                    "unused"
                }
            )
        }

        assertEquals(false, tokenRequested)
        assertEquals(
            "Missing GitHub OIDC audience. Configure " +
                "<authentications><authentication><host>...</host><github-oidc><audience>...</audience>" +
                "</github-oidc></authentication></authentications>.",
            exception.message
        )
    }

    @Test
    fun `requires a basic token before requesting any github token`() {
        var tokenRequested = false
        val exception = assertFailsWith<IllegalArgumentException> {
            RegistryAuthenticationSupport.resolve(
                authentications = listOf(
                    githubOidcAuthentication(host = "registry.fluxzero.io"),
                    basicAuthentication(host = "ghcr.io", token = " ")
                ),
                githubToken = {
                    tokenRequested = true
                    "unused"
                }
            )
        }

        assertEquals(false, tokenRequested)
        assertTrue(exception.message.orEmpty().contains("<host>...</host><basic><token>...</token>"))
    }

    @Test
    fun `rejects schemes paths and invalid ports in authentication hosts`() {
        listOf(
            "https://registry.fluxzero.io",
            "registry.fluxzero.io/team",
            "Registry.fluxzero.io",
            "registry.fluxzero.io:0",
            "registry.fluxzero.io:65536"
        ).forEach { host ->
            val exception = assertFailsWith<IllegalArgumentException> {
                RegistryAuthenticationSupport.resolve(
                    authentications = listOf(basicAuthentication(host = host)),
                    githubToken = { "unused" }
                )
            }
            assertTrue(exception.message.orEmpty().contains("Invalid registry authentication host"), host)
        }
    }

    @Test
    fun `requires exactly one authentication mechanism`() {
        listOf(
            Authentication().apply { host = "registry.fluxzero.io" },
            Authentication().apply {
                host = "registry.fluxzero.io"
                basic = BasicAuthenticationConfiguration()
                githubOidc = GitHubOidcAuthenticationConfiguration()
            }
        ).forEach { authentication ->
            val exception = assertFailsWith<IllegalArgumentException> {
                RegistryAuthenticationSupport.resolve(
                    authentications = listOf(authentication),
                    githubToken = { "unused" }
                )
            }
            assertEquals(
                "Configure exactly one of <basic> or <github-oidc> " +
                    "for registry authentication 1 (registry.fluxzero.io).",
                exception.message
            )
        }
    }

    @Test
    fun `rejects duplicate authentications for the same host and port`() {
        var tokenRequests = 0
        val exception = assertFailsWith<IllegalArgumentException> {
            RegistryAuthenticationSupport.resolve(
                authentications = listOf(
                    githubOidcAuthentication(host = "registry.fluxzero.io"),
                    githubOidcAuthentication(host = "registry.fluxzero.io")
                ),
                githubToken = {
                    tokenRequests++
                    "unused"
                }
            )
        }

        assertEquals(
            "Configure exactly one registry authentication per host. Duplicate: registry.fluxzero.io.",
            exception.message
        )
        assertEquals(0, tokenRequests)
    }

    @Test
    fun `plexus binds github oidc as a shaped authentication mechanism`() {
        val authentication = Authentication()
        val configuration = XmlPlexusConfiguration("authentication").apply {
            addChild(XmlPlexusConfiguration("host").apply {
                value = "registry.fluxzero.io"
            })
            addChild(XmlPlexusConfiguration("github-oidc").apply {
                addChild(XmlPlexusConfiguration("audience").apply {
                    value = "https://cloud.fluxzero.io/custom"
                })
            })
        }

        ObjectWithFieldsConverter().processConfiguration(
            DefaultConverterLookup(),
            authentication,
            javaClass.classLoader,
            configuration,
            object : ExpressionEvaluator {
                override fun evaluate(expression: String): Any = expression
                override fun alignToBaseDirectory(path: File): File = path
            }
        )

        assertEquals("registry.fluxzero.io", authentication.host)
        assertEquals("https://cloud.fluxzero.io/custom", authentication.githubOidc?.audience)
    }

    @Test
    fun `plexus binds the authentications list on the publish mojo`() {
        val mojo = PublishPackageMojo()
        val configuration = XmlPlexusConfiguration("configuration").apply {
            addChild(XmlPlexusConfiguration("authentications").apply {
                addChild(XmlPlexusConfiguration("authentication").apply {
                    addChild(XmlPlexusConfiguration("host").apply { value = "registry.fluxzero.io" })
                    addChild(XmlPlexusConfiguration("github-oidc").apply {
                        addChild(XmlPlexusConfiguration("audience").apply {
                            value = "https://cloud.fluxzero.io"
                        })
                    })
                })
                addChild(XmlPlexusConfiguration("authentication").apply {
                    addChild(XmlPlexusConfiguration("host").apply { value = "ghcr.io" })
                    addChild(XmlPlexusConfiguration("basic").apply {
                        addChild(XmlPlexusConfiguration("token").apply { value = "github-token" })
                    })
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

        val field = PublishPackageMojo::class.java.getDeclaredField("authentications").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val authentications = field.get(mojo) as List<Authentication>
        assertEquals(listOf("registry.fluxzero.io", "ghcr.io"), authentications.map { it.host })
        assertEquals("https://cloud.fluxzero.io", authentications[0].githubOidc?.audience)
        assertEquals("github-token", authentications[1].basic?.token)
    }

    private fun basicAuthentication(
        host: String = "registry.fluxzero.io",
        username: String? = null,
        token: String? = "registry-token"
    ): Authentication = Authentication().apply {
        this.host = host
        basic = BasicAuthenticationConfiguration().apply {
            this.username = username
            this.token = token
        }
    }

    private fun githubOidcAuthentication(
        host: String = "registry.fluxzero.io",
        username: String? = null,
        audience: String? = "https://cloud.fluxzero.io"
    ): Authentication = Authentication().apply {
        this.host = host
        githubOidc = GitHubOidcAuthenticationConfiguration().apply {
            this.username = username
            this.audience = audience
        }
    }
}
