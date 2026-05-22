package host.flux.desktop.services

import java.nio.file.Path
import java.nio.file.Paths

data class AppPaths(
    val appDataDir: Path,
    val binDir: Path,
    val cliExecutable: Path,
    val registryFile: Path
) {
    companion object {
        fun detect(
            platform: PlatformTarget = PlatformDetector.detect(),
            homeDir: Path = Paths.get(System.getProperty("user.home")),
            appDataEnv: String? = System.getenv("APPDATA")
        ): AppPaths {
            val appDataDir = when (platform.os) {
                OperatingSystem.WINDOWS -> {
                    val root = appDataEnv?.takeIf { it.isNotBlank() }?.let(Paths::get)
                        ?: homeDir.resolve("AppData").resolve("Roaming")
                    root.resolve("Fluxzero").resolve("Desktop")
                }
                else -> homeDir.resolve(".fluxzero").resolve("desktop")
            }
            val binDir = appDataDir.resolve("bin")
            val executableName = if (platform.os == OperatingSystem.WINDOWS) "fz.exe" else "fz"
            return AppPaths(
                appDataDir = appDataDir,
                binDir = binDir,
                cliExecutable = binDir.resolve(executableName),
                registryFile = appDataDir.resolve("projects.json")
            )
        }
    }
}
