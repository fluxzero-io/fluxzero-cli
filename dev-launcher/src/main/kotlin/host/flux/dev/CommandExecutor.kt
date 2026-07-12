package host.flux.dev

import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

enum class OutputMode {
    INHERIT,
    STDOUT_TO_STDERR
}

fun interface CommandExecutor {
    fun execute(command: List<String>, workingDirectory: Path, outputMode: OutputMode): Int

    fun startDetached(command: List<String>, workingDirectory: Path, outputFile: Path): Long =
        error("Detached processes are not supported by this command executor")

    fun releaseDetached(workingDirectory: Path) {
    }

    fun <T> supervise(onShutdown: () -> Unit, action: () -> T): T = action()
}

class InheritedIoCommandExecutor : CommandExecutor {
    private val activeScope = AtomicReference<ExecutionScope>()

    override fun execute(command: List<String>, workingDirectory: Path, outputMode: OutputMode): Int {
        val scope = activeScope.get()
        return if (scope == null) {
            supervise({ }) { executeInScope(command, workingDirectory, outputMode, activeScope.get()!!) }
        } else {
            executeInScope(command, workingDirectory, outputMode, scope)
        }
    }

    override fun startDetached(command: List<String>, workingDirectory: Path, outputFile: Path): Long {
        java.nio.file.Files.createDirectories(outputFile.parent)
        java.nio.file.Files.writeString(
            outputFile,
            "",
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING
        )
        if (isWindows()) {
            return ProcessBuilder(command)
                .directory(workingDirectory.toFile())
                .redirectInput(ProcessBuilder.Redirect.from(java.io.File("NUL")))
                .redirectOutput(ProcessBuilder.Redirect.appendTo(outputFile.toFile()))
                .redirectError(ProcessBuilder.Redirect.appendTo(outputFile.toFile()))
                .start().pid()
        }
        if (isMac() && !java.lang.Boolean.getBoolean("fluxzero.dev.detach.shell")) {
            return startWithLaunchd(command, workingDirectory, outputFile)
        }
        val bootstrap = ProcessBuilder(
            listOf(
                "/bin/sh", "-c",
                "nohup \"\$@\" </dev/null >>\"\$0\" 2>&1 & echo \$!",
                outputFile.toString()
            ) + command
        )
            .directory(workingDirectory.toFile())
            .redirectError(ProcessBuilder.Redirect.appendTo(outputFile.toFile()))
            .start()
        val pid = bootstrap.inputStream.bufferedReader().readLine()?.trim()?.toLongOrNull()
            ?: error("Detached process bootstrap did not report a PID. See $outputFile")
        check(bootstrap.waitFor(2, TimeUnit.SECONDS) && bootstrap.exitValue() == 0) {
            "Detached process bootstrap failed. See $outputFile"
        }
        return pid
    }

    override fun releaseDetached(workingDirectory: Path) {
        if (!isMac() || java.lang.Boolean.getBoolean("fluxzero.dev.detach.shell")) return
        val domain = "gui/${userId()}"
        ProcessBuilder("/bin/launchctl", "bootout", "$domain/${launchdLabel(workingDirectory)}")
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD).start().run {
                if (!waitFor(1, TimeUnit.SECONDS)) destroyForcibly()
            }
    }

    private fun startWithLaunchd(command: List<String>, workingDirectory: Path, outputFile: Path): Long {
        val uid = userId()
        val domain = "gui/$uid"
        val label = launchdLabel(workingDirectory)
        releaseDetached(workingDirectory)
        val plist = workingDirectory.resolve(".fluxzero/dev/launchd.plist")
        val arguments = listOf("/bin/zsh", "-lc", "exec \"\$@\"", "fluxzero-dev") + command
        val argumentXml = arguments.joinToString("\n") { "      <string>${xml(it)}</string>" }
        java.nio.file.Files.writeString(
            plist,
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            <plist version="1.0">
            <dict>
              <key>Label</key><string>$label</string>
              <key>ProgramArguments</key>
              <array>
            $argumentXml
              </array>
              <key>WorkingDirectory</key><string>${xml(workingDirectory.toString())}</string>
              <key>StandardOutPath</key><string>${xml(outputFile.toString())}</string>
              <key>StandardErrorPath</key><string>${xml(outputFile.toString())}</string>
              <key>RunAtLoad</key><true/>
              <key>KeepAlive</key><false/>
              <key>ProcessType</key><string>Interactive</string>
              <key>ExitTimeOut</key><integer>1</integer>
            </dict>
            </plist>
            """.trimIndent()
        )
        val bootstrap = ProcessBuilder("/bin/launchctl", "bootstrap", domain, plist.toString())
            .redirectErrorStream(true).start()
        val detail = bootstrap.inputStream.bufferedReader().readText()
        check(bootstrap.waitFor(5, TimeUnit.SECONDS) && bootstrap.exitValue() == 0) {
            "Could not register detached Fluxzero dev process: ${detail.trim()}"
        }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            val state = ProcessBuilder("/bin/launchctl", "print", "$domain/$label")
                .redirectErrorStream(true).start()
            val output = state.inputStream.bufferedReader().readText()
            state.waitFor(2, TimeUnit.SECONDS)
            Regex("(?m)^\\s*pid = (\\d+)\\s*$").find(output)?.groupValues?.get(1)?.toLongOrNull()?.let {
                return it
            }
            Thread.sleep(50)
        }
        error("Detached Fluxzero dev process did not report a PID. See $outputFile")
    }

    private fun userId(): String {
        val process = ProcessBuilder("/usr/bin/id", "-u").redirectErrorStream(true).start()
        val value = process.inputStream.bufferedReader().readText().trim()
        check(process.waitFor(2, TimeUnit.SECONDS) && process.exitValue() == 0 && value.isNotBlank()) {
            "Could not determine the current macOS user id"
        }
        return value
    }

    private fun launchdLabel(workingDirectory: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(workingDirectory.toAbsolutePath().normalize().toString().toByteArray())
            .take(10).joinToString("") { "%02x".format(it) }
        return "io.fluxzero.dev.$digest"
    }

    private fun xml(value: String): String = value
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")

    override fun <T> supervise(onShutdown: () -> Unit, action: () -> T): T {
        val scope = ExecutionScope(onShutdown)
        check(activeScope.compareAndSet(null, scope)) { "Command executor is already supervising a launch" }
        val shutdownHook = Thread({ scope.shutdown() }, "fluxzero-dev-launcher-shutdown")
        Runtime.getRuntime().addShutdownHook(shutdownHook)
        return try {
            action()
        } finally {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook)
            } catch (_: IllegalStateException) {
                // JVM shutdown is already executing the hook.
            }
            activeScope.compareAndSet(scope, null)
        }
    }

    private fun executeInScope(
        command: List<String>,
        workingDirectory: Path,
        outputMode: OutputMode,
        scope: ExecutionScope
    ): Int {
        if (scope.shutdownRequested.get()) return 130
        val builder = ProcessBuilder(command)
            .directory(workingDirectory.toFile())
            .redirectInput(ProcessBuilder.Redirect.INHERIT)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
        if (outputMode == OutputMode.INHERIT) {
            builder.redirectOutput(ProcessBuilder.Redirect.INHERIT)
        }
        val attempt = ProcessAttempt()
        check(scope.activeAttempt.compareAndSet(null, attempt)) { "Command executor already has an active child" }
        if (scope.shutdownRequested.get()) {
            attempt.startFinished.countDown()
            scope.activeAttempt.compareAndSet(attempt, null)
            return 130
        }
        return try {
            val process = try {
                builder.start().also(attempt.process::set)
            } finally {
                attempt.startFinished.countDown()
            }
            if (scope.shutdownRequested.get()) stop(process)
            if (outputMode == OutputMode.STDOUT_TO_STDERR) {
                process.inputStream.copyTo(System.err)
            }
            process.waitFor()
        } finally {
            attempt.startFinished.countDown()
            scope.activeAttempt.compareAndSet(attempt, null)
        }
    }

    private inner class ExecutionScope(private val onShutdown: () -> Unit) {
        val shutdownRequested = AtomicBoolean()
        val activeAttempt = AtomicReference<ProcessAttempt>()

        fun shutdown() {
            if (!shutdownRequested.compareAndSet(false, true)) return
            try {
                activeAttempt.get()?.let { attempt ->
                    attempt.startFinished.await(1, TimeUnit.SECONDS)
                    attempt.process.get()?.let(::stop)
                }
            } finally {
                onShutdown()
            }
        }
    }

    private class ProcessAttempt {
        val process = AtomicReference<Process>()
        val startFinished = CountDownLatch(1)
    }

    private fun stop(process: Process) {
        if (!process.isAlive) return
        process.destroy()
        if (process.waitFor(Duration.ofMillis(1500).toMillis(), TimeUnit.MILLISECONDS)) return

        val descendants = process.descendants().toList().asReversed()
        descendants.filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly)
        process.destroyForcibly()
        process.waitFor(Duration.ofMillis(500).toMillis(), TimeUnit.MILLISECONDS)
        descendants.filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly)
        process.descendants().toList().asReversed().filter(ProcessHandle::isAlive)
            .forEach(ProcessHandle::destroyForcibly)
        if (process.isAlive) {
            process.destroyForcibly()
        }
    }

    private fun isWindows() = System.getProperty("os.name").lowercase().contains("win")

    private fun isMac() = System.getProperty("os.name").lowercase().contains("mac")
}
