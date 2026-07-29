package host.flux.publishing

import com.google.cloud.tools.jib.api.buildplan.FileEntriesLayer
import com.google.cloud.tools.jib.http.Authorization
import java.io.EOFException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.text.Charsets.UTF_8
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JavaPackagePublishSpecTest {
    @Test
    fun defaultsJibHttpTimeoutWithoutOverridingExplicitConfiguration() {
        val previous = System.getProperty(JibHttpTimeout.PROPERTY)
        try {
            System.clearProperty(JibHttpTimeout.PROPERTY)

            JibHttpTimeout.configureDefault()
            assertEquals("60000", System.getProperty(JibHttpTimeout.PROPERTY))

            System.setProperty(JibHttpTimeout.PROPERTY, "90000")
            JibHttpTimeout.configureDefault()
            assertEquals("90000", System.getProperty(JibHttpTimeout.PROPERTY))
        } finally {
            if (previous == null) {
                System.clearProperty(JibHttpTimeout.PROPERTY)
            } else {
                System.setProperty(JibHttpTimeout.PROPERTY, previous)
            }
        }
    }

    @Test
    fun defaultsBasicUsernameToEmpty() {
        val credential = credential(password = "registry-token")

        credential.validate()

        assertEquals("", credential.username)
        assertEquals(
            "Basic " + Base64.getEncoder().encodeToString(":registry-token".toByteArray(UTF_8)),
            Authorization.fromBasicCredentials(credential.username, credential.password).toString()
        )
    }

    @Test
    fun acceptsOnlyLowercaseRegistryHostsWithOptionalPorts() {
        listOf("registry.fluxzero.io", "127.0.0.1:8443", "registry.fluxzero.io:443").forEach { host ->
            credential(host = host).validate()
        }

        listOf(
            "https://registry.fluxzero.io",
            "registry.fluxzero.io/path",
            "Registry.fluxzero.io",
            " registry.fluxzero.io",
            "registry.fluxzero.io ",
            "user@registry.fluxzero.io",
            "registry.fluxzero.io:0",
            "registry.fluxzero.io:65536"
        ).forEach { host ->
            assertThrows(IllegalArgumentException::class.java, { credential(host = host).validate() }, host)
        }
    }

    @Test
    fun rejectsBlankBaseImage() {
        val classesDirectory = Files.createTempDirectory("fluxzero-publish-classes")

        assertThrows(IllegalArgumentException::class.java) {
            publishSpec(classesDirectory, baseImage = "").validate()
        }
    }

    @Test
    fun requiresExplicitImagesButAllowsAnonymousAccess() {
        val classesDirectory = Files.createTempDirectory("fluxzero-publish-classes")

        assertThrows(IllegalArgumentException::class.java) {
            publishSpec(classesDirectory, images = emptyList()).validate()
        }

        val anonymousSpec = publishSpec(classesDirectory, credentials = emptyList())
        anonymousSpec.validate()
        assertNull(anonymousSpec.credentialFor("registry.fluxzero.io/team/service"))
    }

    @Test
    fun resolvesEveryTagForEveryImage() {
        val classesDirectory = Files.createTempDirectory("fluxzero-publish-classes")
        val spec = publishSpec(
            classesDirectory = classesDirectory,
            images = listOf(
                "registry.fluxzero.io/org-a/service",
                "ghcr.io/fluxzero-io/dashboard-fluxzero-io-service"
            ),
            tags = listOf("1.0.0", "sha-1234567"),
            credentials = listOf(
                credential("registry.fluxzero.io", "registry-user", "fluxzero-token"),
                credential("ghcr.io", "github-actor", "github-token")
            )
        )

        assertEquals(
            listOf(
                "registry.fluxzero.io/org-a/service:1.0.0",
                "registry.fluxzero.io/org-a/service:sha-1234567",
                "ghcr.io/fluxzero-io/dashboard-fluxzero-io-service:1.0.0",
                "ghcr.io/fluxzero-io/dashboard-fluxzero-io-service:sha-1234567"
            ),
            spec.packageReferences().map { it.reference }
        )
        assertEquals(
            "github-token",
            spec.credentialFor("ghcr.io/fluxzero-io/dashboard-fluxzero-io-service")?.password
        )
    }

    @Test
    fun resolvesOnePublishTargetPerImageWithAdditionalTags() {
        val classesDirectory = Files.createTempDirectory("fluxzero-publish-classes")
        val spec = publishSpec(
            classesDirectory = classesDirectory,
            images = listOf(
                "registry.fluxzero.io/org-a/service",
                "ghcr.io/fluxzero-io/dashboard-fluxzero-io-service"
            ),
            tags = listOf("1.0.0", "sha-1234567"),
            credentials = listOf(
                credential("registry.fluxzero.io", "registry-user", "fluxzero-token"),
                credential("ghcr.io", "github-actor", "github-token")
            )
        )

        val targets = spec.publishTargets()

        assertEquals(2, targets.size)
        assertEquals("registry.fluxzero.io/org-a/service:1.0.0", targets[0].primaryReference.reference)
        assertEquals(listOf("sha-1234567"), targets[0].additionalTags)
        assertEquals(
            listOf(
                "registry.fluxzero.io/org-a/service:1.0.0",
                "registry.fluxzero.io/org-a/service:sha-1234567"
            ),
            targets[0].references.map { it.reference }
        )
        assertEquals("ghcr.io/fluxzero-io/dashboard-fluxzero-io-service:1.0.0", targets[1].primaryReference.reference)
        assertEquals(listOf("sha-1234567"), targets[1].additionalTags)
    }

    @Test
    fun matchesAuthenticationByExactHostAndPort() {
        val classesDirectory = Files.createTempDirectory("fluxzero-publish-classes")
        val spec = publishSpec(
            classesDirectory = classesDirectory,
            images = listOf("registry.fluxzero.io:8443/team/service"),
            credentials = listOf(
                credential("registry.fluxzero.io", password = "default-port"),
                credential("registry.fluxzero.io:8443", password = "custom-port")
            )
        )

        assertEquals(
            "custom-port",
            spec.credentialFor("registry.fluxzero.io:8443/team/service")?.password
        )
    }

    @Test
    fun allowsAnonymousAccessForImagesWithoutMatchingCredential() {
        val classesDirectory = Files.createTempDirectory("fluxzero-publish-classes")
        val spec = publishSpec(
            classesDirectory = classesDirectory,
            images = listOf(
                "registry.fluxzero.io/team/service",
                "ghcr.io/fluxzero-io/service"
            ),
            credentials = listOf(credential("registry.fluxzero.io"))
        )

        spec.validate()

        assertEquals("registry-token", spec.credentialFor("registry.fluxzero.io/team/service")?.password)
        assertNull(spec.credentialFor("ghcr.io/fluxzero-io/service"))
    }

    @Test
    fun rejectsDuplicateCredentialsForOneHost() {
        val classesDirectory = Files.createTempDirectory("fluxzero-publish-classes")
        val spec = publishSpec(
            classesDirectory = classesDirectory,
            credentials = listOf(
                credential("registry.fluxzero.io", password = "first"),
                credential("registry.fluxzero.io", password = "second")
            )
        )

        assertThrows(IllegalArgumentException::class.java) {
            spec.validate()
        }
    }

    @Test
    fun rejectsUnusedCredentialsAndDuplicateTargets() {
        val classesDirectory = Files.createTempDirectory("fluxzero-publish-classes")

        assertThrows(IllegalArgumentException::class.java) {
            publishSpec(
                classesDirectory = classesDirectory,
                credentials = listOf(credential(), credential("ghcr.io"))
            ).validate()
        }
        assertThrows(IllegalArgumentException::class.java) {
            publishSpec(
                classesDirectory = classesDirectory,
                images = listOf(
                    "registry.fluxzero.io/team/service",
                    "registry.fluxzero.io/team/service"
                )
            ).validate()
        }
        assertThrows(IllegalArgumentException::class.java) {
            publishSpec(
                classesDirectory = classesDirectory,
                tags = listOf("1.0.0", "1.0.0")
            ).validate()
        }
    }

    @Test
    fun rejectsInvalidPublishRetrySettings() {
        val classesDirectory = Files.createTempDirectory("fluxzero-publish-classes")

        assertEquals(10, publishSpec(classesDirectory).publishAttempts)
        assertThrows(IllegalArgumentException::class.java) {
            publishSpec(classesDirectory, publishAttempts = 0).validate()
        }
        assertThrows(IllegalArgumentException::class.java) {
            publishSpec(classesDirectory, publishRetryDelayMillis = -1).validate()
        }
    }

    @Test
    fun rejectsInvalidImageRepositories() {
        listOf(
            "registry.fluxzero.io/Upper/service",
            "registry.fluxzero.io/org/service:tag",
            "registry.fluxzero.io/org/service@sha256:abc",
            "registry.fluxzero.io/org/service name",
            "registry.fluxzero.io:0/org/service",
            "registry.fluxzero.io"
        ).forEach { image ->
            assertThrows(IllegalArgumentException::class.java, {
                JavaPackageReference(image, "$image:1.0.0")
            }, image)
        }
    }

    @Test
    fun parsesBaseImageSourceAliases() {
        assertEquals(BaseImageSource.REGISTRY, BaseImageSource.parse("registry"))
        assertEquals(BaseImageSource.DOCKER_DAEMON, BaseImageSource.parse("docker-daemon"))
        assertEquals(BaseImageSource.DOCKER_DAEMON, BaseImageSource.parse("docker_daemon"))
        assertEquals(BaseImageSource.DOCKER_DAEMON, BaseImageSource.parse("docker"))
    }

    @Test
    fun defaultsToLinuxAmd64AndAcceptsMultipleUniquePlatforms() {
        val classesDirectory = Files.createTempDirectory("fluxzero-publish-classes")

        assertEquals(listOf(JavaPackagePlatform("linux", "amd64")), publishSpec(classesDirectory).platforms)

        publishSpec(
            classesDirectory = classesDirectory,
            platforms = listOf(
                JavaPackagePlatform("linux", "amd64"),
                JavaPackagePlatform("linux", "arm64")
            )
        ).validate()
    }

    @Test
    fun rejectsMissingDuplicateOrUnsupportedPlatforms() {
        val classesDirectory = Files.createTempDirectory("fluxzero-publish-classes")

        listOf(
            emptyList(),
            listOf(JavaPackagePlatform("linux", "amd64"), JavaPackagePlatform("linux", "amd64")),
            listOf(JavaPackagePlatform("windows", "amd64")),
            listOf(JavaPackagePlatform("linux", "ARM 64"))
        ).forEach { platforms ->
            assertThrows(IllegalArgumentException::class.java) {
                publishSpec(classesDirectory, platforms = platforms).validate()
            }
        }
    }

    @Test
    fun appliesCustomLabelsAfterDefaultsAndSupportsRemoval() {
        val classesDirectory = Files.createTempDirectory("fluxzero-publish-classes")
        val spec = publishSpec(
            classesDirectory = classesDirectory,
            defaultLabels = mapOf(
                "org.opencontainers.image.revision" to "original-revision",
                "org.opencontainers.image.source" to "https://example.com/source"
            ),
            labels = mapOf(
                "org.opencontainers.image.revision" to "overridden-revision",
                "org.opencontainers.image.source" to null,
                "example.custom" to "custom-value",
                "io.fluxzero.package.metadata-version" to null
            )
        )

        assertEquals(
            mapOf(
                "org.opencontainers.image.title" to "service",
                "org.opencontainers.image.version" to "1.0.0",
                "org.opencontainers.image.revision" to "overridden-revision",
                "example.custom" to "custom-value"
            ),
            spec.resolvedLabels()
        )
    }

    @Test
    fun canOmitEveryDefaultLabelBeforeApplyingCustomLabels() {
        val classesDirectory = Files.createTempDirectory("fluxzero-publish-classes")
        val spec = publishSpec(
            classesDirectory = classesDirectory,
            includeDefaultLabels = false,
            defaultLabels = mapOf("org.opencontainers.image.source" to "https://example.com/source"),
            labels = mapOf("example.custom" to "custom-value")
        )

        assertEquals(mapOf("example.custom" to "custom-value"), spec.resolvedLabels())
    }

    @Test
    fun hasDefaultJavaToolOptions() {
        assertEquals("-XX:MaxRAMPercentage=75.0", JavaPackagePublishSpec.DEFAULT_JAVA_TOOL_OPTIONS.substringBefore(" "))
    }

    @Test
    fun detectsRetriableRegistryBlobUploadFailures() {
        val exception = RuntimeException(
            "Tried to push BLOB for ghcr.io/org/repo with digest sha256:abc but failed because: blob unknown to registry",
            RuntimeException(
                """
                404 Not Found
                PUT https://ghcr.io/v2/org/repo/blobs/upload/1.uuid?digest=sha256:abc
                {"errors":[{"code":"BLOB_UPLOAD_UNKNOWN","message":"blob upload unknown to registry"}]}
                """.trimIndent()
            )
        )

        assertTrue(exception.isRetriableRegistryPublishFailure())
    }

    @Test
    fun detectsRetriableRegistryTransportFailures() {
        listOf(
            SocketTimeoutException("Read timed out"),
            RuntimeException("containerization failed", ConnectException("Connection reset")),
            EOFException("Unexpected end of stream")
        ).forEach { exception ->
            assertTrue(exception.isRetriableRegistryPublishFailure(), exception.toString())
        }
    }

    @Test
    fun doesNotRetryUnrelatedRegistryFailures() {
        val exception = RuntimeException("authentication failed for ghcr.io/org/repo")

        assertFalse(exception.isRetriableRegistryPublishFailure())
    }

    @Test
    fun placesDependenciesBelowApplicationWhileKeepingApplicationFirstOnClasspath() {
        val classesDirectory = Files.createTempDirectory("fluxzero-publish-classes")
        Files.writeString(classesDirectory.resolve("Application.class"), "compiled-application")
        val dependenciesDirectory = Files.createTempDirectory("fluxzero-publish-dependencies")
        val firstDependency = dependenciesDirectory.resolve("first.jar")
        val secondDependency = dependenciesDirectory.resolve("second.jar")
        Files.writeString(firstDependency, "first-dependency")
        Files.writeString(secondDependency, "second-dependency")

        val plan = JavaPackagePublisher().buildPlan(
            publishSpec(
                classesDirectory = classesDirectory,
                dependencies = listOf(
                    JavaPackageDependency(firstDependency),
                    JavaPackageDependency(secondDependency)
                )
            )
        )

        assertEquals(
            listOf("dependencies", "application"),
            plan.layers.filterIsInstance<FileEntriesLayer>().map { it.name }
        )
        assertEquals(
            listOf("java", "-cp", "/app/classes:/app/libs/first.jar:/app/libs/second.jar", "com.example.Application"),
            plan.entrypoint
        )
    }

    @Test
    fun buildsContainerPlanWithReproducibleTimestampsAndSortedApplicationEntries() {
        val classesDirectory = Files.createTempDirectory("fluxzero-publish-classes")
        Files.createDirectories(classesDirectory.resolve("z"))
        Files.writeString(classesDirectory.resolve("z/Z.class"), "compiled-z")
        Files.createDirectories(classesDirectory.resolve("a"))
        Files.writeString(classesDirectory.resolve("a/A.class"), "compiled-a")
        val dependenciesDirectory = Files.createTempDirectory("fluxzero-publish-dependencies")
        val directDependency = dependenciesDirectory.resolve("direct.jar")
        val transitiveDependency = dependenciesDirectory.resolve("transitive.jar")
        Files.writeString(directDependency, "direct-dependency")
        Files.writeString(transitiveDependency, "transitive-dependency")

        val plan = JavaPackagePublisher().buildPlan(
            publishSpec(
                classesDirectory = classesDirectory,
                dependencies = listOf(
                    JavaPackageDependency(directDependency),
                    JavaPackageDependency(transitiveDependency)
                )
            )
        )

        val fileEntriesLayers = plan.layers.filterIsInstance<FileEntriesLayer>()
        val dependencyEntries = fileEntriesLayers.first { it.name == "dependencies" }.entries
        val applicationEntries = fileEntriesLayers.first { it.name == "application" }.entries

        assertEquals(JavaPackagePublishSpec.REPRODUCIBLE_CONTAINER_TIMESTAMP, plan.creationTime)
        assertTrue(fileEntriesLayers.flatMap { it.entries }.all { it.modificationTime == JavaPackagePublishSpec.REPRODUCIBLE_FILE_TIMESTAMP })
        assertEquals(
            listOf("/app/classes/a/A.class", "/app/classes/z/Z.class"),
            applicationEntries.map { it.extractionPath.toString() }
        )
        assertEquals(
            listOf("/app/libs/direct.jar", "/app/libs/transitive.jar"),
            dependencyEntries.map { it.extractionPath.toString() }
        )
    }

    @Test
    fun copiesExtraDirectoriesIntoSeparateDeterministicLayers() {
        val classesDirectory = Files.createTempDirectory("fluxzero-publish-classes")
        Files.writeString(classesDirectory.resolve("Application.class"), "compiled-application")
        val configDirectory = Files.createTempDirectory("fluxzero-publish-config")
        Files.writeString(configDirectory.resolve("z.properties"), "z=true")
        Files.createDirectories(configDirectory.resolve("nested"))
        Files.writeString(configDirectory.resolve("nested/a.properties"), "a=true")

        val plan = JavaPackagePublisher().buildPlan(
            publishSpec(
                classesDirectory = classesDirectory,
                extraDirectories = listOf(JavaPackageExtraDirectory(configDirectory, "/app/config/"))
            )
        )

        val layers = plan.layers.filterIsInstance<FileEntriesLayer>()
        val extraEntries = layers.first { it.name == "extra-directory-1" }.entries
        assertEquals(listOf("extra-directory-1", "application"), layers.map { it.name })
        assertEquals(
            listOf("/app/config/nested/a.properties", "/app/config/z.properties"),
            extraEntries.map { it.extractionPath.toString() }
        )
        assertTrue(extraEntries.all { it.modificationTime == JavaPackagePublishSpec.REPRODUCIBLE_FILE_TIMESTAMP })
    }

    @Test
    fun rejectsMissingOrOverlappingExtraDirectories() {
        val classesDirectory = Files.createTempDirectory("fluxzero-publish-classes")
        val configDirectory = Files.createTempDirectory("fluxzero-publish-config")
        val missingDirectory = configDirectory.resolve("missing")

        assertThrows(IllegalArgumentException::class.java) {
            publishSpec(
                classesDirectory,
                extraDirectories = listOf(JavaPackageExtraDirectory(missingDirectory, "/app/config"))
            ).validate()
        }
        assertThrows(IllegalArgumentException::class.java) {
            publishSpec(
                classesDirectory,
                extraDirectories = listOf(JavaPackageExtraDirectory(configDirectory, "/app/classes/config"))
            ).validate()
        }
        assertThrows(IllegalArgumentException::class.java) {
            publishSpec(
                classesDirectory,
                extraDirectories = listOf(
                    JavaPackageExtraDirectory(configDirectory, "/app/config"),
                    JavaPackageExtraDirectory(configDirectory, "/app/config/nested")
                )
            ).validate()
        }
    }

    private fun publishSpec(
        classesDirectory: Path,
        images: List<String> = listOf("registry.fluxzero.io/team/service"),
        credentials: List<JavaPackageRegistryCredential> = listOf(credential()),
        tags: List<String> = emptyList(),
        baseImage: String = JavaPackagePublishSpec.DEFAULT_BASE_IMAGE,
        platforms: List<JavaPackagePlatform> = listOf(JavaPackagePlatform.DEFAULT),
        dependencies: List<JavaPackageDependency> = emptyList(),
        extraDirectories: List<JavaPackageExtraDirectory> = emptyList(),
        includeDefaultLabels: Boolean = true,
        defaultLabels: Map<String, String> = emptyMap(),
        labels: Map<String, String?> = emptyMap(),
        publishAttempts: Int = JavaPackagePublishSpec.DEFAULT_PUBLISH_ATTEMPTS,
        publishRetryDelayMillis: Long = JavaPackagePublishSpec.DEFAULT_PUBLISH_RETRY_DELAY_MILLIS
    ): JavaPackagePublishSpec = JavaPackagePublishSpec(
        packageName = "service",
        packageVersion = "1.0.0",
        mainClass = "com.example.Application",
        classesDirectory = classesDirectory,
        images = images,
        credentials = credentials,
        tags = tags,
        baseImage = baseImage,
        platforms = platforms,
        dependencies = dependencies,
        extraDirectories = extraDirectories,
        includeDefaultLabels = includeDefaultLabels,
        defaultLabels = defaultLabels,
        labels = labels,
        publishAttempts = publishAttempts,
        publishRetryDelayMillis = publishRetryDelayMillis
    )

    private fun credential(
        host: String = "registry.fluxzero.io",
        username: String = "",
        password: String = "registry-token"
    ): JavaPackageRegistryCredential = JavaPackageRegistryCredential(host, username, password)
}
