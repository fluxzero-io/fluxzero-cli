package host.flux.desktop.services

enum class OperatingSystem {
    MACOS,
    WINDOWS,
    LINUX
}

enum class CpuArchitecture {
    AMD64,
    ARM64
}

data class PlatformTarget(
    val os: OperatingSystem,
    val arch: CpuArchitecture
) {
    val cliReleaseAssetName: String
        get() = when (os) {
            OperatingSystem.MACOS -> when (arch) {
                CpuArchitecture.AMD64 -> "flux-macos-amd64"
                CpuArchitecture.ARM64 -> "flux-macos-arm64"
            }
            OperatingSystem.WINDOWS -> "flux-windows-amd64.exe"
            OperatingSystem.LINUX -> when (arch) {
                CpuArchitecture.AMD64 -> "flux-linux-amd64"
                CpuArchitecture.ARM64 -> "flux-linux-arm64"
            }
        }
}

object PlatformDetector {
    fun detect(osName: String = System.getProperty("os.name"), osArch: String = System.getProperty("os.arch")): PlatformTarget {
        val os = when {
            osName.contains("windows", ignoreCase = true) -> OperatingSystem.WINDOWS
            osName.contains("mac", ignoreCase = true) || osName.contains("darwin", ignoreCase = true) -> OperatingSystem.MACOS
            osName.contains("linux", ignoreCase = true) -> OperatingSystem.LINUX
            else -> error("Unsupported operating system: $osName")
        }
        val arch = when {
            osArch.equals("amd64", ignoreCase = true) || osArch.equals("x86_64", ignoreCase = true) -> CpuArchitecture.AMD64
            osArch.equals("aarch64", ignoreCase = true) || osArch.equals("arm64", ignoreCase = true) -> CpuArchitecture.ARM64
            else -> error("Unsupported CPU architecture: $osArch")
        }
        return PlatformTarget(os, arch)
    }
}
