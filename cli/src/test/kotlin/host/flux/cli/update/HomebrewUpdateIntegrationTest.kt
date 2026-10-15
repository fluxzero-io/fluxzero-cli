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

    @Test fun `real Homebrew upgrades the snapshot after the live formula changes major`() {
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
            return """
                class $className < Formula
                  desc "Isolated CLI update regression fixture"
                  homepage "https://fluxzero.io"
                  url "${binary.toUri()}"
                  version "$version"
                  sha256 "$digest"
                  license "MIT"
                  keg_only "Isolated update test"
                  def install
                    bin.install "probe-$version" => "fz"
                  end
                end
            """.trimIndent()
        }
        try {
            Files.writeString(source, formula("1.2.3"))
            run("install", "--formula", qualified)
            val installation = Path.of(run("--prefix", qualified).trim()).resolve("bin/fz").toRealPath()
            assertEquals("1.2.3", runUpdateCommand(listOf(installation.toString(), "--version"), env).trim())
            Files.writeString(source, formula("1.2.4"))
            val nextMajor = formula("2.0.0")
            var usedSnapshot = false
            val installer = CliUpdateInstaller(
                installation = CliInstallation(installation, false),
                releases = { CliRelease("1.2.4", emptyList()) },
                allowed = { true }, environment = emptyMap(), homebrewFormula = qualified,
                packageManager = ManagedPackageInstallation.HOMEBREW,
                command = { command, extraEnv ->
                    if (command[1] == "update") "" // Fixture has no remote metadata to refresh.
                    else {
                        if (command[1] == "upgrade") {
                            assertNotEquals(source.toString(), command.last())
                            assertTrue(command.last().endsWith(".rb"))
                            Files.writeString(source, nextMajor)
                            usedSnapshot = true
                        }
                        runUpdateCommand(if (command.first() == "brew") listOf(brew) + command.drop(1) else command, env + extraEnv)
                    }
                },
            )
            assertContains(installer.update("1.2.3"), "through Homebrew")
            assertTrue(usedSnapshot)
            val prefix = Path.of(run("--prefix", qualified).trim())
            assertEquals("1.2.4", runUpdateCommand(listOf(prefix.resolve("bin/fz").toString(), "--version"), env).trim())
            assertContains(Files.readString(prefix.resolve("INSTALL_RECEIPT.json")), tapName)
            assertEquals("1.2.3", runUpdateCommand(listOf(installation.toString(), "--version"), env).trim())
        } finally {
            try { runCatching { run("uninstall", "--force", "--formula", qualified) } }
            finally { Files.walk(tap).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
        }
    }
}
