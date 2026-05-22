package host.flux.desktop.services

import host.flux.desktop.model.CommandResult
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

interface CommandRunner {
    fun run(command: List<String>, workingDir: Path? = null, timeout: Duration = Duration.ofMinutes(5)): CommandResult
}

class ProcessCommandRunner : CommandRunner {
    override fun run(command: List<String>, workingDir: Path?, timeout: Duration): CommandResult {
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .apply {
                if (workingDir != null) {
                    directory(workingDir.toFile())
                }
            }
            .start()
        process.outputStream.close()
        val outputFuture = CompletableFuture.supplyAsync {
            process.inputStream.readBytes().toString(StandardCharsets.UTF_8)
        }

        val completed = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)
        if (!completed) {
            process.destroyForcibly()
            return CommandResult(
                exitCode = -1,
                output = "Command timed out after ${timeout.seconds} seconds: ${command.joinToString(" ")}"
            )
        }

        val output = outputFuture.get(5, TimeUnit.SECONDS)
        return CommandResult(process.exitValue(), output)
    }
}
