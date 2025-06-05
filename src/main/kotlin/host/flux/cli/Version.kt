package host.flux.cli

import com.github.ajalt.clikt.core.CliktCommand

class Version(private val versionProvider: () -> String = { defaultVersion() }) : CliktCommand(help = "Print the release version of the flux-cli") {
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
