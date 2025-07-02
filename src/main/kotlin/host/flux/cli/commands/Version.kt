package host.flux.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context

class Version(
    private val versionProvider: () -> String = { defaultVersion() }
) : CliktCommand() {

    override fun help(context: Context): String = "Print the release version of the fluxzero-cli"

    override fun run() {
        echo(versionProvider())
    }

    companion object {
        private fun defaultVersion(): String {
            // Implementation-Version is set by the build system when the jar is assembled
            return Version::class.java.`package`.implementationVersion ?: "dev"
        }
    }
}