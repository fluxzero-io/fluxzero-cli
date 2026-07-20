package host.flux.cli.services

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ManagedPackageInstallationTest {
    @TempDir
    lateinit var tempDirectory: Path

    @Test
    fun `detects Homebrew executable through bin symlink`() {
        val executable = tempDirectory.resolve("Cellar/fluxzero/1.4.1/bin/fz")
        Files.createDirectories(executable.parent)
        Files.writeString(executable, "binary")
        val command = tempDirectory.resolve("bin/fz")
        Files.createDirectories(command.parent)
        Files.createSymbolicLink(command, executable)

        assertEquals(
            ManagedPackageInstallation.HOMEBREW,
            ManagedPackageInstallation.detect(command)
        )
    }

    @Test
    fun `detects WinGet portable executable`() {
        val executable = Path.of(
            "C:\\Users\\rene\\AppData\\Local\\Microsoft\\WinGet\\Packages\\" +
                "Fluxzero.FluxzeroCLI_Microsoft.Winget.Source_8wekyb3d8bbwe\\fz.exe"
        )

        assertEquals(
            ManagedPackageInstallation.WINGET,
            ManagedPackageInstallation.detect(executable)
        )
    }

    @Test
    fun `leaves direct installations self managed`() {
        assertNull(ManagedPackageInstallation.detect(tempDirectory.resolve(".fluxzero/bin/fz")))
    }
}
