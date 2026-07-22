package host.flux.publishing

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.Attributes
import java.util.jar.Manifest

class PackageNameSupportTest {
    @Test
    fun buildsPackageReferenceFromRegistryHost() {
        assertEquals(
            "registry.fluxzero.io/service:1.2.3",
            PackageNameSupport.packageReference("registry.fluxzero.io", "service", "1.2.3")
        )
    }

    @Test
    fun buildsPackageReferenceWithTeamId() {
        assertEquals(
            "registry.fluxzero.io/team-a/service:1.2.3",
            PackageNameSupport.packageReference("registry.fluxzero.io", "team-a", "service", "1.2.3")
        )
    }

    @Test
    fun preservesRegistryPort() {
        assertEquals(
            "localhost:8443/service:dev",
            PackageNameSupport.packageReference("localhost:8443", "service", "dev")
        )
    }

    @Test
    fun validatesPackageNamesAndSanitizesDefaultPackageVersion() {
        assertTrue(PackageNameSupport.isValidPackageName("my-service-1"))
        assertTrue(PackageNameSupport.isValidTeamId("team-a"))
        assertEquals("1.0-SNAPSHOT", PackageNameSupport.defaultPackageVersion("1.0-SNAPSHOT"))
        assertEquals("rc1", PackageNameSupport.defaultPackageVersion(".rc1"))
        assertFalse(PackageNameSupport.isValidPackageName("My-Service-1"))
        assertFalse(PackageNameSupport.isValidTeamId("team-a/service"))
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

    @Test
    fun normalizesVendorNeutralRepositoryUrls() {
        assertEquals(
            "https://code.example.org/team/service",
            PackageNameSupport.normalizeRepositoryUrl("https://code.example.org/team/service.git")
        )
        assertEquals(
            "ssh://code.example.org/team/service",
            PackageNameSupport.normalizeRepositoryUrl("git@code.example.org:team/service.git")
        )
        assertEquals(
            "https://code.example.org/team/service",
            PackageNameSupport.normalizeRepositoryUrl(
                "https://build-user:secret-token@code.example.org/team/service.git?credential=secret"
            )
        )
        assertNull(PackageNameSupport.normalizeRepositoryUrl("../service"))
        assertNull(PackageNameSupport.normalizeRepositoryUrl("file:///tmp/service"))
    }

    @Test
    fun readsFullRevisionAndOriginFromGitWithoutCiEnvironmentVariables() {
        val repository = Files.createTempDirectory("fluxzero-git-info")
        git(repository, "init", "-q")
        git(repository, "config", "user.name", "Fluxzero Test")
        git(repository, "config", "user.email", "test@fluxzero.local")
        Files.writeString(repository.resolve("README.md"), "test")
        git(repository, "add", "README.md")
        git(repository, "commit", "-q", "-m", "initial")
        git(repository, "remote", "add", "origin", "git@code.example.org:team/service.git")

        val gitInfo = PackageNameSupport.gitInfo(repository)

        assertNotNull(gitInfo)
        assertTrue(gitInfo!!.sha!!.matches(Regex("[0-9a-f]{40,64}")))
        assertTrue(gitInfo.sha!!.startsWith(gitInfo.shortSha!!))
        assertEquals("ssh://code.example.org/team/service", gitInfo.remoteUrl)
    }

    private fun git(repository: Path, vararg arguments: String) {
        val process = ProcessBuilder(listOf("git", "-C", repository.toString()) + arguments)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        assertEquals(0, process.waitFor(), output)
    }
}
