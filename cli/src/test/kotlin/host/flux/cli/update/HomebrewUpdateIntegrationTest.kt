package host.flux.cli.update

import host.flux.cli.services.ManagedPackageInstallation
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import kotlin.test.*

/** Uses a private, keg-only fixture in the real Homebrew installation; never installs or changes Fluxzero itself. */
@EnabledIfEnvironmentVariable(named = "FLUXZERO_TEST_HOMEBREW", matches = ".+")
class HomebrewUpdateIntegrationTest {
    @TempDir lateinit var directory: Path

    @Test fun `real Homebrew automatically installs a patch`() = verifyUpdate("1.2.4")
    @Test fun `real Homebrew automatically installs a minor`() = verifyUpdate("1.3.0")
    @Test fun `real Homebrew refuses a concurrent major and still permits a manual upgrade`() =
        verifyUpdate("1.2.4", concurrentMajor = true)

    private fun verifyUpdate(target: String, concurrentMajor: Boolean = false) {
        val brew = System.getenv("FLUXZERO_TEST_HOMEBREW")
        val env = mapOf("HOMEBREW_NO_AUTO_UPDATE" to "1", "HOMEBREW_NO_INSTALL_CLEANUP" to "1",
            "HOMEBREW_NO_ANALYTICS" to "1", "HOMEBREW_NO_INSTALLED_DEPENDENTS_CHECK" to "1", "NONINTERACTIVE" to "1", "XDG_CONFIG_HOME" to directory.resolve("config").toString())
        fun run(vararg arguments: String): String = runUpdateCommand(listOf(brew) + arguments, env)
        val id = UUID.randomUUID().toString().take(8)
        val tapName = "fluxzero-test/update-$id"
        val name = "fz-update-probe-$id"
        val qualified = "$tapName/$name"
        val tap = Path.of(run("--repository").trim()).resolve("Library/Taps/fluxzero-test/homebrew-update-$id")
        val source = tap.resolve("Formula/$name.rb")
        Files.createDirectories(source.parent)
        fun formula(version: String): String {
            val binary = directory.resolve("probe-$version")
            Files.writeString(binary, "#!/bin/sh\nprintf '$version\\n'\n")
            val digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(binary)).joinToString("") { "%02x".format(it) }
            val className = name.split('-').joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }
            val template = Path.of("../packaging/homebrew/fluxzero.rb.template").toAbsolutePath().normalize()
            val rendered = Files.readString(template)
                .replace("class Fluxzero < Formula", "class $className < Formula")
                .replace("  desc ", "  keg_only \"Isolated update test\"\n  desc ")
                .replace(Regex("https://github.com/fluxzero-io/fluxzero-cli/releases/download/@VERSION@/flux-[a-z0-9-]+"), binary.toUri().toString())
                .replace(Regex("@[A-Z0-9_]+SHA256@"), digest)
                .replace("  def install", "  version \"$version\"\n  def install")
            // Keep the actual production guard; only substitute fixture assets and names.
            return rendered.replace("bin.install binary => \"fz\"", "bin.install \"probe-$version\" => \"fz\"")
        }

        try {
            Files.writeString(source, formula("1.2.3"))
            run("install", "--formula", qualified)
            val installation = Path.of(run("--prefix", qualified).trim()).resolve("bin/fz").toRealPath()
            assertEquals("1.2.3", runUpdateCommand(listOf(installation.toString(), "--version"), env).trim())
            Files.writeString(source, formula(target))
            val nextMajor = formula("2.0.0")
            var invokedUpgrade = false
            val installer = CliUpdateInstaller(
                installation = CliInstallation(installation, false),
                releases = { CliRelease(target, emptyList()) },
                allowed = { true }, environment = emptyMap(), homebrewFormula = qualified,
                packageManager = ManagedPackageInstallation.HOMEBREW,
                command = { command, extraEnv ->
                    if (command[1] == "update") "" // Fixture has no remote metadata to refresh.
                    else {
                        if (command[1] == "upgrade") {
                            assertEquals(qualified, command.last())
                            assertContains(command, "--with-cli-update-guard")
                            assertEquals(target, extraEnv["HOMEBREW_FLUXZERO_CLI_UPDATE_VERSION"])
                            if (concurrentMajor) Files.writeString(source, nextMajor)
                            invokedUpgrade = true
                        }
                        runUpdateCommand(if (command.first() == "brew") listOf(brew) + command.drop(1) else command, env + extraEnv)
                    }
                },
            )
            if (concurrentMajor) {
                val failure = assertFailsWith<IllegalStateException> { installer.update("1.2.3") }
                assertContains(failure.message.orEmpty(), "CLI update deferred")
                val prefix = Path.of(run("--prefix", qualified).trim())
                assertEquals("1.2.3", runUpdateCommand(listOf(prefix.resolve("bin/fz").toString(), "--version"), env).trim())
                // No automatic-update version is supplied for an explicit package-manager upgrade.
                run("upgrade", "--formula", qualified)
                assertEquals("2.0.0", runUpdateCommand(listOf(prefix.resolve("bin/fz").toString(), "--version"), env).trim())
            } else {
                assertContains(installer.update("1.2.3"), "through Homebrew")
                val prefix = Path.of(run("--prefix", qualified).trim())
                assertEquals(target, runUpdateCommand(listOf(prefix.resolve("bin/fz").toString(), "--version"), env).trim())
                assertContains(Files.readString(prefix.resolve("INSTALL_RECEIPT.json")), tapName)
                // A guard flag stored in the receipt must not prevent a later explicit major upgrade.
                Files.writeString(source, nextMajor)
                run("upgrade", "--formula", qualified)
                assertEquals("2.0.0", runUpdateCommand(listOf(prefix.resolve("bin/fz").toString(), "--version"), env).trim())
            }
            assertTrue(invokedUpgrade)
            assertEquals("1.2.3", runUpdateCommand(listOf(installation.toString(), "--version"), env).trim())
        } finally {
            try { runCatching { run("uninstall", "--force", "--formula", qualified) } }
            finally { Files.walk(tap).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
        }
    }
}
