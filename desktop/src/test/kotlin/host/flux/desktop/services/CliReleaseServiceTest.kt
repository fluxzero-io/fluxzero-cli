package host.flux.desktop.services

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import host.flux.desktop.model.CommandResult

class CliReleaseServiceTest {
    @Test
    fun parsesReleaseAndSelectsMatchingAsset() {
        val release = CliReleaseClient.parseRelease(
            """
            {
              "tag_name": "1.2.3",
              "assets": [
                { "name": "flux-macos-arm64", "browser_download_url": "https://example.test/flux-macos-arm64" },
                { "name": "flux-windows-amd64.exe", "browser_download_url": "https://example.test/flux-windows-amd64.exe" }
              ]
            }
            """.trimIndent()
        )

        val target = PlatformTarget(OperatingSystem.MACOS, CpuArchitecture.ARM64)

        assertEquals("1.2.3", release.tagName)
        assertEquals("https://example.test/flux-macos-arm64", release.downloadUrlFor(target))
    }

    @Test
    fun fallsBackToCanonicalReleaseUrlWhenAssetListIsMissing() {
        val release = CliRelease(tagName = "1.2.3", assets = emptyList())
        val target = PlatformTarget(OperatingSystem.WINDOWS, CpuArchitecture.AMD64)

        assertEquals(
            "https://github.com/fluxzero-io/fluxzero-cli/releases/download/1.2.3/flux-windows-amd64.exe",
            release.downloadUrlFor(target)
        )
    }

    @Test
    fun parsesTemplateNamesFromCliOutput() {
        val tempDir = createTempDirectory()
        val cli = tempDir.resolve("fz")
        cli.writeText("fake")
        val runtime = CliRuntimeService(
            paths = AppPaths(
                appDataDir = tempDir,
                binDir = tempDir,
                cliExecutable = cli,
                registryFile = tempDir.resolve("projects.json")
            ),
            commandRunner = FakeCommandRunner { _, _ ->
                CommandResult(
                    0,
                    """
                    A new version of fluxzero-cli is available: 1.2.4 (current: 1.2.3)
                    flux-basic-java: Basic Java project
                    flux-kotlin-single
                    """.trimIndent()
                )
            }
        )

        assertEquals(listOf("flux-basic-java", "flux-kotlin-single"), runtime.listTemplates())
    }
}
