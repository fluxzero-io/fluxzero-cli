package host.flux.desktop.services

import host.flux.desktop.model.CommandResult
import java.nio.file.Path
import java.time.Duration

class FakeCommandRunner(
    private val handler: (List<String>, Path?) -> CommandResult = { _, _ -> CommandResult(0, "") }
) : CommandRunner {
    val commands = mutableListOf<List<String>>()

    override fun run(command: List<String>, workingDir: Path?, timeout: Duration): CommandResult {
        commands += command
        return handler(command, workingDir)
    }
}
