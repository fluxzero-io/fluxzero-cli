package host.flux.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import host.flux.cli.services.VersionService

class Version(
    private val versionService: VersionService = VersionService
) : CliktCommand() {
    override val hiddenFromHelp: Boolean = true

    override fun help(context: Context): String = "Print the installed Fluxzero CLI version"

    override fun run() {
        echo(versionService.getCurrentVersion())
    }
}
