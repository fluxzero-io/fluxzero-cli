package host.flux.publishing

import com.google.cloud.tools.jib.api.buildplan.FileEntriesLayer
import java.nio.file.Files
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JavaPackagePublishSpecTest {
    @Test
    fun rejectsPlainHttpRegistryHost() {
        val classesDirectory = Files.createTempDirectory("fluxzero-publish-classes")

        assertThrows(IllegalArgumentException::class.java) {
            JavaPackagePublishSpec(
                registryHost = "http://localhost:8080",
                registryToken = "token",
                packageName = "service",
                packageVersion = "1.0.0",
                mainClass = "com.example.Application",
                classesDirectory = classesDirectory
            ).validate()
        }
    }

    @Test
    fun rejectsBlankBaseImage() {
        val classesDirectory = Files.createTempDirectory("fluxzero-publish-classes")

        assertThrows(IllegalArgumentException::class.java) {
            JavaPackagePublishSpec(
                registryHost = "registry.fluxzero.io",
                registryToken = "token",
                packageName = "service",
                packageVersion = "1.0.0",
                mainClass = "com.example.Application",
                baseImage = "",
                classesDirectory = classesDirectory
            ).validate()
        }
    }

    @Test
    fun rejectsInvalidTeamId() {
        val classesDirectory = Files.createTempDirectory("fluxzero-publish-classes")

        assertThrows(IllegalArgumentException::class.java) {
            JavaPackagePublishSpec(
                registryHost = "registry.fluxzero.io",
                registryToken = "token",
                teamId = "team-a/service",
                packageName = "service",
                packageVersion = "1.0.0",
                mainClass = "com.example.Application",
                classesDirectory = classesDirectory
            ).validate()
        }
    }

    @Test
    fun resolvesMultipleImagesAndTags() {
        val classesDirectory = Files.createTempDirectory("fluxzero-publish-classes")

        val spec = JavaPackagePublishSpec(
            packageName = "service",
            packageVersion = "1.0.0",
            mainClass = "com.example.Application",
            classesDirectory = classesDirectory,
            images = listOf(
                "registry.fluxzero.io/org-a/service",
                "ghcr.io/fluxzero-io/dashboard-fluxzero-io-service"
            ),
            tags = listOf("1.0.0", "sha-1234567"),
            credentials = listOf(
                JavaPackageRegistryCredential("registry.fluxzero.io", "github-ci", "fluxzero-token"),
                JavaPackageRegistryCredential("ghcr.io", "github-actor", "github-token")
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
        assertEquals("github-token", spec.credentialFor("ghcr.io/fluxzero-io/dashboard-fluxzero-io-service").registryToken)
    }

    @Test
    fun resolvesOnePublishTargetPerImageWithAdditionalTags() {
        val classesDirectory = Files.createTempDirectory("fluxzero-publish-classes")

        val spec = JavaPackagePublishSpec(
            packageName = "service",
            packageVersion = "1.0.0",
            mainClass = "com.example.Application",
            classesDirectory = classesDirectory,
            images = listOf(
                "registry.fluxzero.io/org-a/service",
                "ghcr.io/fluxzero-io/dashboard-fluxzero-io-service"
            ),
            tags = listOf("1.0.0", "sha-1234567"),
            credentials = listOf(
                JavaPackageRegistryCredential("registry.fluxzero.io", "github-ci", "fluxzero-token"),
                JavaPackageRegistryCredential("ghcr.io", "github-actor", "github-token")
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
        assertEquals(
            listOf(
                "ghcr.io/fluxzero-io/dashboard-fluxzero-io-service:1.0.0",
                "ghcr.io/fluxzero-io/dashboard-fluxzero-io-service:sha-1234567"
            ),
            targets[1].references.map { it.reference }
        )
    }

    @Test
    fun resolvesLegacySingleTargetReferenceAndCredential() {
        val classesDirectory = Files.createTempDirectory("fluxzero-publish-classes")

        val spec = JavaPackagePublishSpec(
            registryHost = "registry.fluxzero.io",
            registryUsername = "github-ci",
            registryToken = "registry-token",
            teamId = "958e1ee2f6c64facbc7765026a9a6e09",
            packageName = "service",
            packageVersion = "1.0.0",
            mainClass = "com.example.Application",
            classesDirectory = classesDirectory
        )

        assertEquals(
            listOf("registry.fluxzero.io/958e1ee2f6c64facbc7765026a9a6e09/service:1.0.0"),
            spec.packageReferences().map { it.reference }
        )
        assertEquals("github-ci", spec.credentialFor("registry.fluxzero.io/958e1ee2f6c64facbc7765026a9a6e09/service").registryUsername)
        assertEquals("registry-token", spec.credentialFor("registry.fluxzero.io/958e1ee2f6c64facbc7765026a9a6e09/service").registryToken)
    }

    @Test
    fun rejectsImageWithoutMatchingCredentialDuringValidation() {
        val classesDirectory = Files.createTempDirectory("fluxzero-publish-classes")

        val spec = JavaPackagePublishSpec(
            packageName = "service",
            packageVersion = "1.0.0",
            mainClass = "com.example.Application",
            classesDirectory = classesDirectory,
            images = listOf(
                "registry.fluxzero.io/958e1ee2f6c64facbc7765026a9a6e09/service",
                "ghcr.io/fluxzero-io/service"
            ),
            credentials = listOf(
                JavaPackageRegistryCredential("registry.fluxzero.io", "github-ci", "fluxzero-token")
            )
        )

        assertThrows(IllegalArgumentException::class.java) {
            spec.validate()
        }
    }

    @Test
    fun rejectsInvalidImageRepositories() {
        listOf(
            "registry.fluxzero.io/Upper/service",
            "registry.fluxzero.io/org/service:tag",
            "registry.fluxzero.io/org/service@sha256:abc",
            "registry.fluxzero.io/org/service name",
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
    fun hasDefaultJavaToolOptions() {
        assertEquals("-XX:MaxRAMPercentage=75.0", JavaPackagePublishSpec.DEFAULT_JAVA_TOOL_OPTIONS.substringBefore(" "))
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
            JavaPackagePublishSpec(
                registryHost = "registry.fluxzero.io",
                registryToken = "token",
                packageName = "service",
                packageVersion = "1.0.0",
                mainClass = "com.example.Application",
                classesDirectory = classesDirectory,
                dependencies = listOf(
                    JavaPackageDependency(directDependency),
                    JavaPackageDependency(transitiveDependency)
                )
            )
        )

        val fileEntriesLayers = plan.layers.filterIsInstance<FileEntriesLayer>()
        val entrypoint = plan.entrypoint
        val dependencyEntries = fileEntriesLayers
            .first { it.name == "dependencies" }
            .entries
        val applicationEntries = fileEntriesLayers
            .first { it.name == "application" }
            .entries

        assertEquals(JavaPackagePublishSpec.REPRODUCIBLE_CONTAINER_TIMESTAMP, plan.creationTime)
        assertTrue(fileEntriesLayers.flatMap { it.entries }.all { it.modificationTime == JavaPackagePublishSpec.REPRODUCIBLE_FILE_TIMESTAMP })
        assertEquals(listOf("application", "dependencies"), fileEntriesLayers.map { it.name })
        assertEquals(
            listOf("/app/classes/a/A.class", "/app/classes/z/Z.class"),
            applicationEntries.map { it.extractionPath.toString() }
        )
        assertEquals(
            listOf("/app/libs/direct.jar", "/app/libs/transitive.jar"),
            dependencyEntries.map { it.extractionPath.toString() }
        )
        assertEquals(
            listOf("java", "-cp", "/app/classes:/app/libs/direct.jar:/app/libs/transitive.jar", "com.example.Application"),
            entrypoint
        )
    }
}
