package host.flux.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import host.flux.cli.services.InstallationService
import host.flux.cli.services.DefaultInstallationService
import host.flux.cli.services.InstallResult

class Upgrade(
    private val installer: InstallationService = DefaultInstallationService(),
) : CliktCommand() {

    override fun help(context: Context): String = "Upgrade the Fluxzero CLI"

    override fun run() {
        try {
            echo("Checking Fluxzero CLI installation...")
            when (val result = installer.install()) {
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
