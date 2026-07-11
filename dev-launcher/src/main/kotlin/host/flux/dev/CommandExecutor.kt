package host.flux.dev

import java.nio.file.Path
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
}
