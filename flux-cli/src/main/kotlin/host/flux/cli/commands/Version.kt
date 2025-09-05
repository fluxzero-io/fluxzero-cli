package host.flux.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import host.flux.templates.services.VersionService

class Version(
    private val versionService: VersionService = VersionService
) : CliktCommand() {

    override fun help(context: Context): String = "Print the release version of the fluxzero-cli"

    override fun run() {
        echo(versionService.getCurrentVersion())
    }
}