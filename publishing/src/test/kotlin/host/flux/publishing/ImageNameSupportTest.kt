package host.flux.publishing

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.jar.Attributes
import java.util.jar.Manifest

class ImageNameSupportTest {
    @Test
    fun buildsImageReferenceFromRegistryHostWithScheme() {
        assertEquals(
            "registry.fluxzero.io/service:1.2.3",
            ImageNameSupport.imageReference("https://registry.fluxzero.io", "service", "1.2.3")
        )
    }

    @Test
    fun preservesRegistryPortAndDetectsPlainHttpRegistryHost() {
        assertEquals(
            "localhost:8443/service:dev",
            ImageNameSupport.imageReference("https://localhost:8443", "service", "dev")
        )
        assertTrue(ImageNameSupport.isPlainHttpRegistryHost("http://localhost:8080"))
        assertFalse(ImageNameSupport.isPlainHttpRegistryHost("https://registry.fluxzero.io"))
        assertFalse(ImageNameSupport.isPlainHttpRegistryHost("registry.fluxzero.io"))
    }

    @Test
    fun validatesImageNamesAndSanitizesDefaultImageVersion() {
        assertTrue(ImageNameSupport.isValidImageName("my-service-1"))
        assertEquals("1.0-SNAPSHOT", ImageNameSupport.defaultImageVersion("1.0-SNAPSHOT"))
        assertEquals("rc1", ImageNameSupport.defaultImageVersion(".rc1"))
        assertFalse(ImageNameSupport.isValidImageName("My-Service-1"))
    }

    @Test
    fun generatesVendorNeutralImageVersionFromGitInfoAndUtcTime() {
        val clock = Clock.fixed(Instant.parse("2026-06-14T10:42:15Z"), ZoneOffset.UTC)

        assertEquals(
            "dev-feature-publish-20260614104215-abcdef123456",
            ImageNameSupport.automaticImageVersion(
                clock,
                ImageNameSupport.GitInfo(
                    branch = "feature/publish",
                    shortSha = "abcdef1234567890",
                    dirty = false
                )
            )
        )
    }

    @Test
    fun rejectsDirtyGitInfoUnlessExplicitlyAllowed() {
        val clock = Clock.fixed(Instant.parse("2026-06-14T10:42:15Z"), ZoneOffset.UTC)
        val dirtyGitInfo = ImageNameSupport.GitInfo(
            branch = "feature/publish",
            shortSha = "abcdef1234567890",
            dirty = true
        )

        val error = assertThrows(IllegalStateException::class.java) {
            ImageNameSupport.automaticImageVersion(clock, dirtyGitInfo)
        }
        assertTrue(error.message!!.contains("dirty git worktree"))

        assertEquals(
            "dev-feature-publish-20260614104215-abcdef123456-dirty",
            ImageNameSupport.automaticImageVersion(clock, dirtyGitInfo, allowDirty = true)
        )
    }

    @Test
    fun marksExplicitImageVersionWhenDirtyIsAllowed() {
        val dirtyGitInfo = ImageNameSupport.GitInfo(
            branch = "feature/publish",
            shortSha = "abcdef1234567890",
            dirty = true
        )

        assertEquals(
            "1.2.3-dirty",
            ImageNameSupport.markDirtyImageVersion("1.2.3", dirtyGitInfo, allowDirty = true)
        )
        assertEquals(
            "1.2.3-dirty",
            ImageNameSupport.markDirtyImageVersion("1.2.3-dirty", dirtyGitInfo, allowDirty = true)
        )
        assertEquals(
            "1.2.3",
            ImageNameSupport.markDirtyImageVersion(
                "1.2.3",
                dirtyGitInfo.copy(dirty = false),
                allowDirty = false
            )
        )
    }

    @Test
    fun automaticImageVersionRequiresGitCommit() {
        val clock = Clock.fixed(Instant.parse("2026-06-14T10:42:15Z"), ZoneOffset.UTC)

        val error = assertThrows(IllegalStateException::class.java) {
            ImageNameSupport.automaticImageVersion(clock, null)
        }
        assertTrue(error.message!!.contains("git commit"))
    }

    @Test
    fun prefersStartClassAndIgnoresBlankValues() {
        val manifest = Manifest()
        manifest.mainAttributes.put(Attributes.Name.MANIFEST_VERSION, "1.0")
        manifest.mainAttributes.put(Attributes.Name.MAIN_CLASS, "com.example.Application")
        manifest.mainAttributes.putValue("Start-Class", " ")

        assertEquals("com.example.Application", ImageNameSupport.mainClassFromManifest(manifest.mainAttributes))

        manifest.mainAttributes.putValue("Start-Class", "com.example.BootApplication")
        assertEquals("com.example.BootApplication", ImageNameSupport.mainClassFromManifest(manifest.mainAttributes))
    }
}
