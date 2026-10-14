package host.flux.cli.update

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*

@EnabledOnOs(OS.WINDOWS)
@EnabledIfEnvironmentVariable(named = "FLUXZERO_UPDATE_OLD_EXE", matches = ".+")
class WindowsNativeUpdateIntegrationTest {
    @TempDir lateinit var directory: Path

    @Test fun `real native executables survive contention and upgrade after the old process exits`() {
        val old = Path.of(System.getenv("FLUXZERO_UPDATE_OLD_EXE"))
        val newer = Path.of(System.getenv("FLUXZERO_UPDATE_NEW_EXE"))
        val target = directory.resolve("fz.exe")
        val alias = directory.resolve("fluxzero.exe")
        Files.copy(old, target)
        Files.copy(old, alias)
        Files.list(old.parent).use { paths ->
            paths.filter { it.fileName.toString().endsWith(".dll", true) }.forEach { Files.copy(it, directory.resolve(it.fileName)) }
        }
        val archive = directory.resolve("update.zip")
        ZipOutputStream(Files.newOutputStream(archive)).use { zip ->
            zip.putNextEntry(ZipEntry("fz.exe")); Files.copy(newer, zip); zip.closeEntry()
            Files.list(newer.parent).use { paths ->
                paths.filter { it.fileName.toString().endsWith(".dll", true) }.forEach {
                    zip.putNextEntry(ZipEntry(it.fileName.toString())); Files.copy(it, zip); zip.closeEntry()
                }
            }
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(archive)).joinToString("") { "%02x".format(it) }
        fun version(path: Path) = runUpdateCommand(listOf(path.toString(), "--version"), emptyMap()).trim()
        fun update() = CliUpdateInstaller(
            installation = CliInstallation(target, false),
            releases = { CliRelease("1.2.4", listOf(CliAsset("bundle.zip", "fixture", "sha256:$digest"))) },
            download = { _, path -> Files.copy(archive, path, REPLACE_EXISTING); Unit },
            allowed = { true }, nativeAsset = "bundle.zip",
        ).update("1.2.3")
        assertEquals("1.2.3", version(target))
        val worker = copyWindowsWorker(CliInstallation(target, false), directory)
        assertEquals("1.2.3", version(worker.path))
        val log = directory.resolve("mcp.log")
        val running = ProcessBuilder(target.toString(), "-Duser.home=$directory", "mcp")
            .directory(directory.toFile()).redirectOutput(log.toFile()).redirectError(log.toFile())
            .apply { environment()["CI"] = "true" }.start()
        try {
            Thread.sleep(700)
            assertTrue(running.isAlive, Files.readString(log))
            runCatching { update() }
            assertTrue(running.isAlive)
            assertTrue(version(target) in setOf("1.2.3", "1.2.4"))
            assertTrue(version(alias) in setOf("1.2.3", "1.2.4"))
        } finally { running.destroyForcibly(); assertTrue(running.waitFor(10, TimeUnit.SECONDS)) }
        // Recreate the old installation to prove a complete successful replacement independently of contention.
        Files.copy(old, target, REPLACE_EXISTING)
        Files.copy(old, alias, REPLACE_EXISTING)
        assertContains(update(), "updated to 1.2.4")
        assertEquals("1.2.4", version(target))
        assertEquals("1.2.4", version(alias))
    }
}
