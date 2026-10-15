package host.flux.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import host.flux.cli.services.InstallationService
import host.flux.cli.services.DefaultInstallationService
import host.flux.cli.services.InstallResult
import host.flux.cli.update.CliUpdateSettings
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice

class Upgrade(
    private val installer: InstallationService = DefaultInstallationService(),
    private val updateSettings: CliUpdateSettings = CliUpdateSettings(),
) : CliktCommand() {
    private val autoUpdate by option("--auto-update", help = "Configure background CLI updates (every three days, within the current stable major). Does not upgrade now.")
        .choice("enable", "disable", "status", "enable-if-unset")

    override fun help(context: Context): String = "Upgrade the Fluxzero CLI"

    override fun run() {
        try {
            autoUpdate?.let { action ->
                val enabled = updateSettings.configure(action)
                echo("Automatic CLI updates: ${when (enabled) { true -> "enabled"; false -> "disabled"; null -> "unset (disabled)" }}")
                echo("Checks run at most every three days. FLUXZERO_CLI_AUTO_UPDATE=false and CI=true suppress background updates.")
                return
            }
            echo("Checking Fluxzero CLI installation...")
            when (val result = updateSettings.withInstallationLock { installer.install() }) {
                is InstallResult.Upgraded -> {
                    echo("✅ Fluxzero CLI upgraded from ${result.fromVersion} to ${result.toVersion}")
                }
                is InstallResult.FreshInstall -> {
                    echo("✅ Fluxzero CLI installed (version: ${result.version})")
                }
                is InstallResult.ManagedUpgrade -> {
                    echo("Fluxzero CLI update completed using ${result.packageManager}")
                }
                is InstallResult.AlreadyLatest -> 
                    echo("Fluxzero CLI is already up to date (current version: ${result.currentVersion})")
            }
        } catch (e: Exception) {
            throw UsageError(e.message ?: "Fluxzero CLI upgrade failed")
        }
    }
}
