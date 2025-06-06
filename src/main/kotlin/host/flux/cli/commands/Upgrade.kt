package host.flux.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import host.flux.cli.install.DefaultInstaller
import host.flux.cli.install.Installer

class Upgrade(
    private val installer: Installer = DefaultInstaller(),
) : CliktCommand() {

    override fun help(context: Context): String = "Download and install the latest flux-cli release"

    override fun run() {
        val version = installer.installLatest()
        echo("flux-cli upgraded to $version")
    }
}
