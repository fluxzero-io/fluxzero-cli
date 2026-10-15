package host.flux.cli.update

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*

class CliUpdateInstallerTest {
    @TempDir lateinit var directory: Path
    private val bytes = "new executable".toByteArray()
    private fun release(digest: String = "sha256:" + MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }) =
        CliRelease("v1.2.4", listOf(CliAsset("test-native", "unused", digest)))

    private fun binary(): Path = directory.resolve("fz").also { Files.writeString(it, "old executable") }
    private fun installer(target: Path, release: CliRelease = release(), allowed: () -> Boolean = { true },
                          command: (List<String>, Map<String, String>) -> String = { _, _ -> "1.2.4" }) =
        CliUpdateInstaller(CliInstallation(target, false), { release }, { _, path -> Files.write(path, bytes); Unit },
            command, allowed, emptyMap(), false, "test-native")

    @Test fun `verified update replaces only the current installation and matching alias`() {
        val target = binary()
        val alias = directory.resolve("fluxzero")
        Files.copy(target, alias)
        val other = directory.resolve("other"); Files.writeString(other, "unrelated")
        installer(target).update("1.2.3")
        assertContentEquals(bytes, Files.readAllBytes(target))
        assertContentEquals(bytes, Files.readAllBytes(alias))
        assertEquals("unrelated", Files.readString(other))
        assertTrue(Files.isExecutable(target))
    }

    @Test fun `checksum failure missing digest version mismatch and opt-out preserve old installation`() {
        val target = binary()
        assertFails { installer(target, release("sha256:" + "0".repeat(64))).update("1.2.3") }
        assertFails { installer(target, release("")).update("1.2.3") }
        assertFails { installer(target, command = { _, _ -> "2.0.0" }).update("1.2.3") }
        var checks = 0
        assertFails { installer(target, allowed = { ++checks == 1 }).update("1.2.3") }
        assertEquals("old executable", Files.readString(target))
        Files.list(directory).use { assertEquals(listOf("fz"), it.map { p -> p.fileName.toString() }.toList()) }
    }

    @Test fun `a modified alias is not overwritten`() {
        val target = binary()
        Files.writeString(directory.resolve("fluxzero"), "custom wrapper")
        installer(target).update("1.2.3")
        assertEquals("custom wrapper", Files.readString(directory.resolve("fluxzero")))
    }

    @Test fun `an installation changed by another installer is not overwritten`() {
        val target = binary()
        assertFails {
            installer(target, command = { _, _ -> Files.writeString(target, "external upgrade"); "1.2.4" }).update("1.2.3")
        }
        assertEquals("external upgrade", Files.readString(target))
    }

    @Test fun `staging failure cannot replace any target`() {
        val target = binary()
        val source = directory.resolve("new"); Files.write(source, bytes)
        assertFails { replaceInstallation(linkedMapOf(target to source, directory.resolve("missing/alias") to source)) }
        assertEquals("old executable", Files.readString(target))
    }

    @Test fun `partial replacement is rolled back if another target cannot be replaced`() {
        val target = binary()
        val source = directory.resolve("new"); Files.write(source, bytes)
        val invalidTarget = Files.createDirectory(directory.resolve("directory"))
        Files.writeString(invalidTarget.resolve("keep"), "unchanged")
        assertFails { replaceInstallation(linkedMapOf(target to source, invalidTarget to source)) }
        assertEquals("old executable", Files.readString(target))
        assertEquals("unchanged", Files.readString(invalidTarget.resolve("keep")))
    }

    private fun brewInfo(version: String = "1.2.4", pinned: Boolean = false) =
        """{"formulae":[{"full_name":"fluxzero-io/tap/fluxzero","pinned":$pinned,"versions":{"stable":"$version"},"installed":[{"version":"1.2.3"}]}]}"""

    @Test fun `Homebrew checks pins and version before upgrading with metadata refresh disabled`() {
        val target = directory.resolve("Cellar/fluxzero/1.2.3/bin/fz")
        val calls = mutableListOf<List<String>>()
        val formula = directory.resolve("fluxzero.rb")
        Files.writeString(formula, brewInfo())
        val service = installer(target, command = { command, env ->
            calls += command
            assertEquals("1", env["HOMEBREW_NO_AUTO_UPDATE"])
            assertEquals("1", env["HOMEBREW_NO_INSTALL_CLEANUP"])
            when (command[1]) {
                "info" -> if (command.last().endsWith(".rb")) Files.readString(Path.of(command.last())) else brewInfo()
                "formula" -> formula.toString()
                "upgrade" -> {
                    // Change the live tap between validation and install: only the frozen file may be used.
                    Files.writeString(formula, brewInfo("2.0.0"))
                    assertEquals(brewInfo(), Files.readString(Path.of(command.last())))
                    ""
                }
                "--prefix" -> directory.toString()
                "--version" -> "1.2.4"
                else -> ""
            }
        })
        assertContains(service.update("1.2.3"), "through Homebrew")
        assertEquals(1, calls.count { it.getOrNull(1) == "upgrade" })
        assertTrue(calls.indexOfFirst { it[1] == "update" } < calls.indexOfFirst { it[1] == "upgrade" })
    }

    @Test fun `Homebrew pins and a formula moving to another major skip installation`() {
        for (info in listOf(brewInfo(pinned = true), brewInfo("2.0.0"))) {
            val target = directory.resolve("Cellar/fluxzero/1.2.3/bin/fz")
            val source = directory.resolve("fluxzero.rb")
            Files.writeString(source, info)
            installer(target, command = { command, _ ->
                assertNotEquals("upgrade", command[1])
                when (command[1]) { "info" -> info; "formula" -> source.toString(); else -> "" }
            }).update("1.2.3")
        }
    }

    @Test fun `WinGet pins skip upgrades and unpinned upgrades specify the selected version`() {
        val target = directory.resolve("Microsoft/WinGet/Packages/Fluxzero.FluxzeroCLI/fz.exe")
        val calls = mutableListOf<List<String>>()
        installer(target, command = { command, _ ->
            calls += command
            if (command[1] == "pin") "Fluxzero.FluxzeroCLI" else fail("pinned package must not update")
        }).update("1.2.3")
        assertEquals(1, calls.size)
        calls.clear()
        installer(target, command = { command, _ ->
            calls += command
            if (command[1] == "--version") "1.2.4" else ""
        }).update("1.2.3")
        val upgrade = calls.single { it.getOrNull(1) == "upgrade" }
        assertEquals("1.2.4", upgrade[upgrade.indexOf("--version") + 1])
        assertContains(upgrade, "--disable-interactivity")
        assertFalse(upgrade.contains("--force"))
    }

    @Test fun `Windows packages retain DLLs and reject path traversal`() {
        val zip = directory.resolve("package.zip")
        fun archive(vararg names: String) {
            ZipOutputStream(Files.newOutputStream(zip)).use { out ->
                names.forEach { name -> out.putNextEntry(ZipEntry(name)); out.write(bytes); out.closeEntry() }
            }
        }
        archive("../fz.exe")
        assertFails { extractWindowsPackage(zip, directory) }
        archive("fz.exe", "VCRUNTIME140.dll")
        assertEquals(directory.resolve("fz.exe"), extractWindowsPackage(zip, directory))
        assertContentEquals(bytes, Files.readAllBytes(directory.resolve("VCRUNTIME140.dll")))
    }
    @Test fun `Windows runtime changes are rejected before touching executables`() {
        val installed = Files.createDirectory(directory.resolve("installed"))
        val stage = Files.createDirectory(directory.resolve("stage"))
        Files.writeString(installed.resolve("fz.exe"), "original")
        val dll = stage.resolve("runtime.dll")
        Files.writeString(dll, "new runtime")
        assertFails { requireUnchangedWindowsRuntime(stage, installed) }
        Files.writeString(installed.resolve("runtime.dll"), "old runtime")
        assertFails { requireUnchangedWindowsRuntime(stage, installed) }
        assertEquals("original", Files.readString(installed.resolve("fz.exe")))
        Files.copy(dll, installed.resolve("runtime.dll"), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        requireUnchangedWindowsRuntime(stage, installed)
    }

}
