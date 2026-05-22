package host.flux.desktop.services

import kotlin.test.Test
import kotlin.test.assertEquals

class PlatformDetectorTest {
    @Test
    fun detectsMacAppleSiliconAsset() {
        val target = PlatformDetector.detect(osName = "Mac OS X", osArch = "aarch64")

        assertEquals(OperatingSystem.MACOS, target.os)
        assertEquals(CpuArchitecture.ARM64, target.arch)
        assertEquals("flux-macos-arm64", target.cliReleaseAssetName)
    }

    @Test
    fun selectsWindowsAmd64AssetOnWindowsArm() {
        val target = PlatformDetector.detect(osName = "Windows 11", osArch = "arm64")

        assertEquals(OperatingSystem.WINDOWS, target.os)
        assertEquals(CpuArchitecture.ARM64, target.arch)
        assertEquals("flux-windows-amd64.exe", target.cliReleaseAssetName)
    }

    @Test
    fun resolvesWindowsAppDataDirectory() {
        val paths = AppPaths.detect(
            platform = PlatformTarget(OperatingSystem.WINDOWS, CpuArchitecture.AMD64),
            homeDir = java.nio.file.Path.of("C:/Users/alice"),
            appDataEnv = "C:/Users/alice/AppData/Roaming"
        )

        assertEquals(
            java.nio.file.Path.of("C:/Users/alice/AppData/Roaming/Fluxzero/Desktop/projects.json"),
            paths.registryFile
        )
        assertEquals("fz.exe", paths.cliExecutable.fileName.toString())
    }
}
