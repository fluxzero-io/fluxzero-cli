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

class PackageNameSupportTest {
    @Test
    fun buildsPackageReferenceFromRegistryHostWithScheme() {
        assertEquals(
            "registry.fluxzero.io/service:1.2.3",
            PackageNameSupport.packageReference("https://registry.fluxzero.io", "service", "1.2.3")
        )
    }

    @Test
    fun preservesRegistryPortAndDetectsPlainHttpRegistryHost() {
        assertEquals(
            "localhost:8443/service:dev",
            PackageNameSupport.packageReference("https://localhost:8443", "service", "dev")
        )
        assertTrue(PackageNameSupport.isPlainHttpRegistryHost("http://localhost:8080"))
        assertFalse(PackageNameSupport.isPlainHttpRegistryHost("https://registry.fluxzero.io"))
        assertFalse(PackageNameSupport.isPlainHttpRegistryHost("registry.fluxzero.io"))
    }

    @Test
    fun validatesPackageNamesAndSanitizesDefaultPackageVersion() {
        assertTrue(PackageNameSupport.isValidPackageName("my-service-1"))
        assertEquals("1.0-SNAPSHOT", PackageNameSupport.defaultPackageVersion("1.0-SNAPSHOT"))
        assertEquals("rc1", PackageNameSupport.defaultPackageVersion(".rc1"))
        assertFalse(PackageNameSupport.isValidPackageName("My-Service-1"))
    }

    @Test
    fun generatesVendorNeutralPackageVersionFromGitInfoAndUtcTime() {
        val clock = Clock.fixed(Instant.parse("2026-06-14T10:42:15Z"), ZoneOffset.UTC)

        assertEquals(
            "dev-feature-publish-20260614104215-abcdef123456",
            PackageNameSupport.automaticPackageVersion(
                clock,
                PackageNameSupport.GitInfo(
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
        val dirtyGitInfo = PackageNameSupport.GitInfo(
            branch = "feature/publish",
            shortSha = "abcdef1234567890",
            dirty = true
        )

        val error = assertThrows(IllegalStateException::class.java) {
            PackageNameSupport.automaticPackageVersion(clock, dirtyGitInfo)
        }
        assertTrue(error.message!!.contains("dirty git worktree"))

        assertEquals(
            "dev-feature-publish-20260614104215-abcdef123456-dirty",
            PackageNameSupport.automaticPackageVersion(clock, dirtyGitInfo, allowDirty = true)
        )
    }

    @Test
    fun marksExplicitPackageVersionWhenDirtyIsAllowed() {
        val dirtyGitInfo = PackageNameSupport.GitInfo(
            branch = "feature/publish",
            shortSha = "abcdef1234567890",
            dirty = true
        )

        assertEquals(
            "1.2.3-dirty",
            PackageNameSupport.markDirtyPackageVersion("1.2.3", dirtyGitInfo, allowDirty = true)
        )
        assertEquals(
            "1.2.3-dirty",
            PackageNameSupport.markDirtyPackageVersion("1.2.3-dirty", dirtyGitInfo, allowDirty = true)
        )
        assertEquals(
            "1.2.3",
            PackageNameSupport.markDirtyPackageVersion(
                "1.2.3",
                dirtyGitInfo.copy(dirty = false),
                allowDirty = false
            )
        )
    }

    @Test
    fun automaticPackageVersionRequiresGitCommit() {
        val clock = Clock.fixed(Instant.parse("2026-06-14T10:42:15Z"), ZoneOffset.UTC)

        val error = assertThrows(IllegalStateException::class.java) {
            PackageNameSupport.automaticPackageVersion(clock, null)
        }
        assertTrue(error.message!!.contains("git commit"))
    }

    @Test
    fun prefersStartClassAndIgnoresBlankValues() {
        val manifest = Manifest()
        manifest.mainAttributes.put(Attributes.Name.MANIFEST_VERSION, "1.0")
        manifest.mainAttributes.put(Attributes.Name.MAIN_CLASS, "com.example.Application")
        manifest.mainAttributes.putValue("Start-Class", " ")

        assertEquals("com.example.Application", PackageNameSupport.mainClassFromManifest(manifest.mainAttributes))

        manifest.mainAttributes.putValue("Start-Class", "com.example.BootApplication")
        assertEquals("com.example.BootApplication", PackageNameSupport.mainClassFromManifest(manifest.mainAttributes))
    }
}
