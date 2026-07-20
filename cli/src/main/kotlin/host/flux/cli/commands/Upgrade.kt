package host.flux.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import host.flux.cli.services.InstallationService
import host.flux.cli.services.DefaultInstallationService
import host.flux.cli.services.InstallResult

class Upgrade(
    private val installer: InstallationService = DefaultInstallationService(),
) : CliktCommand() {

    override fun help(context: Context): String = "Upgrade fluxzero-cli using its installation method"

    override fun run() {
        try {
            echo("Checking fluxzero-cli installation...")
            when (val result = installer.install()) {
                is InstallResult.Upgraded -> {
                    echo("✅ fluxzero-cli upgraded from ${result.fromVersion} to ${result.toVersion}")
                }
                is InstallResult.FreshInstall -> {
                    echo("✅ fluxzero-cli installed (version: ${result.version})")
                }
                is InstallResult.ExternallyManaged -> {
                    echo("This fluxzero-cli installation is managed by ${result.packageManager}.")
                    echo("Upgrade with: ${result.upgradeCommand}")
                }
                is InstallResult.AlreadyLatest -> 
                    echo("fluxzero-cli is already up to date (current version: ${result.currentVersion})")
            }
        } catch (e: Exception) {
            echo("Error: ${e.message}", err = true)
        }
    }
}
