package host.flux.cli.services

import java.nio.file.Path
import java.nio.file.Paths

internal enum class ManagedPackageInstallation(
    val displayName: String,
    val upgradeCommands: List<List<String>>,
) {
    HOMEBREW(
        "Homebrew",
        listOf(listOf("brew", "upgrade", "fluxzero")),
    ),
    WINGET(
        "WinGet",
        listOf(listOf("winget", "upgrade", "--exact", "--id", "Fluxzero.FluxzeroCLI")),
    );

    companion object {
        fun detect(executablePath: Path?): ManagedPackageInstallation? {
            if (executablePath == null) return null

            val normalized = runCatching { executablePath.toRealPath() }
                .getOrElse { executablePath.toAbsolutePath().normalize() }
                .toString()
                .replace('\\', '/')
                .lowercase()

            return when {
                "/cellar/fluxzero/" in normalized -> HOMEBREW
                "/microsoft/winget/packages/" in normalized -> WINGET
                "/microsoft/winget/links/" in normalized -> WINGET
                else -> null
            }
        }

        fun currentExecutablePath(): Path? = runCatching {
            ProcessHandle.current().info().command().orElse(null)?.let(Paths::get)
        }.getOrNull()
    }
}
