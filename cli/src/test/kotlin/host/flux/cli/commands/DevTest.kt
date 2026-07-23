package host.flux.cli.commands

import com.github.ajalt.clikt.testing.test
import host.flux.dev.CommandExecutor
import host.flux.dev.DevLaunchRequest
import host.flux.dev.DevLaunchTarget
import host.flux.dev.DevLauncher
import host.flux.dev.DevStartupReadiness
import host.flux.dev.OutputMode
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.io.BufferedReader
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DevTest {
    @TempDir
    lateinit var projectDirectory: Path

    private fun mavenProject() {
        Files.writeString(projectDirectory.resolve("pom.xml"), "<project/>")
    }

    @Test
    fun `forwards dev configuration to shared launcher`() {
        mavenProject()
        var request: DevLaunchRequest? = null
        val launcher = DevLauncher { captured -> request = captured; 0 }

        val result = Dev(launcher).test(
            listOf(
                "--project-dir", projectDirectory.toString(),
                "--dev-server-version", "0-SNAPSHOT",
                "--main-class", "com.example.App",
                "--app", "app",
                "--app", "audittrail",
                "--profile", "full-stack",
                "--environment", "local",
                "--port", "4200",
                "--idp", "external",
                "--fast-compiler",
                "--idle-timeout", "4h",
                "--no-tests",
                "--frontend-command", "npm run dev",
                "--frontend-directory", "frontend",
                "--frontend-setup-command", "npm install --prefer-offline --no-audit --no-fund",
                "--backend-path", "/graphql",
                "--app-arg", "--seed"
            )
        )

        assertEquals(0, result.statusCode)
        assertEquals(DevLaunchTarget.SERVER, request?.target)
        assertEquals("0-SNAPSHOT", request?.devServerVersion)
        assertTrue(request!!.arguments.containsAll(listOf("--main-class", "com.example.App", "--fast-compiler")))
        assertTrue(request!!.arguments.containsAll(listOf("--app", "app", "--app", "audittrail")))
        assertTrue(request!!.arguments.containsAll(listOf("--profile", "full-stack")))
        assertTrue(request!!.arguments.containsAll(listOf("--environment", "local")))
        assertTrue(request!!.arguments.containsAll(listOf("--idle-timeout", "4h")))
        assertTrue(request!!.arguments.containsAll(listOf("--port", "4200")))
        assertTrue(request!!.arguments.containsAll(listOf("--idp", "external")))
        assertTrue(request!!.arguments.containsAll(listOf("--frontend-command", "npm run dev", "--backend-path", "/graphql")))
        assertTrue(request!!.arguments.containsAll(listOf("--frontend-directory", "frontend")))
        assertTrue(request!!.arguments.containsAll(listOf(
            "--frontend-setup-command", "npm install --prefer-offline --no-audit --no-fund"
        )))
        assertTrue(request!!.arguments.containsAll(listOf("--no-tests", "--app-arg", "--seed")))
    }

    @Test
    fun `mcp command starts stdio target with only project discovery arguments`() {
        val requests = mutableListOf<DevLaunchRequest>()
        val launcher = DevLauncher { captured -> requests += captured; 0 }
        val root = projectDirectory.toAbsolutePath().normalize()

        val result = Mcp(launcher).test(
            listOf("--project-dir", projectDirectory.toString(), "--dev-server-version", "0-SNAPSHOT")
        )

        assertEquals(0, result.statusCode)
        assertEquals(
            listOf(
                DevLaunchRequest(
                    root,
                    "0-SNAPSHOT",
                    DevLaunchTarget.MCP_STDIO,
                    arguments = listOf("--project-dir", root.toString())
                )
            ),
            requests
        )
    }

    @Test
    fun `mcp command can ensure one background environment before connecting`() {
        val requests = mutableListOf<DevLaunchRequest>()
        val launcher = DevLauncher { captured -> requests += captured; 0 }
        val root = projectDirectory.toAbsolutePath().normalize()

        val result = Mcp(launcher).test(
            listOf("--project-dir", projectDirectory.toString(), "--dev-server-version", "1-SNAPSHOT", "--ensure-dev")
        )

        assertEquals(0, result.statusCode)
        assertEquals(
            listOf(
                DevLaunchRequest(
                    root,
                    "1-SNAPSHOT",
                    DevLaunchTarget.SERVER,
                    detached = true,
                    startupReadiness = DevStartupReadiness.AGENT_CONTROL_PLANE
                ),
                DevLaunchRequest(
                    root,
                    "1-SNAPSHOT",
                    DevLaunchTarget.MCP_STDIO,
                    arguments = listOf("--project-dir", root.toString())
                )
            ),
            requests
        )
    }

    @Test
    fun `mcp command can ensure an empty background environment before connecting`() {
        val requests = mutableListOf<DevLaunchRequest>()
        val launcher = DevLauncher { captured -> requests += captured; 0 }
        val root = projectDirectory.toAbsolutePath().normalize()

        val result = Mcp(launcher).test(
            listOf(
                "--project-dir", projectDirectory.toString(),
                "--dev-server-version", "1-SNAPSHOT",
                "--ensure-dev",
                "--allow-empty"
            )
        )

        assertEquals(0, result.statusCode)
        assertEquals(
            listOf(
                DevLaunchRequest(
                    root,
                    "1-SNAPSHOT",
                    DevLaunchTarget.SERVER,
                    arguments = listOf("--no-compile-on-start"),
                    detached = true,
                    startupReadiness = DevStartupReadiness.AGENT_CONTROL_PLANE
                ),
                DevLaunchRequest(
                    root,
                    "1-SNAPSHOT",
                    DevLaunchTarget.MCP_STDIO,
                    arguments = listOf("--project-dir", root.toString())
                )
            ),
            requests
        )
        assertTrue(requests.none { it.target == DevLaunchTarget.CONTROL })
    }

    @Test
    fun `mcp command retries empty environment readiness before starting stdio`() {
        val sessionDirectory = Files.createDirectories(projectDirectory.resolve(".fluxzero/dev"))
        val sessionFile = sessionDirectory.resolve("session.json")
        Files.writeString(
            sessionFile,
            """{"status":"starting","mcp":{"name":"mcp","state":"running"}}"""
        )
        val requests = mutableListOf<DevLaunchRequest>()
        var mcpAttempts = 0
        var pauses = 0
        val launcher = DevLauncher { captured ->
            requests += captured
            if (captured.target == DevLaunchTarget.MCP_STDIO && mcpAttempts++ == 0) {
                Files.writeString(
                    sessionFile,
                    """{"status":"running","mcp":{"name":"mcp","state":"running"}}"""
                )
                1
            } else {
                0
            }
        }

        val result = Mcp(launcher, readinessAttempts = 3, readinessPause = { pauses++ }).test(
            listOf("--project-dir", projectDirectory.toString(), "--ensure-dev", "--allow-empty")
        )

        assertEquals(0, result.statusCode)
        assertEquals(
            listOf(
                DevLaunchTarget.SERVER,
                DevLaunchTarget.MCP_STDIO,
                DevLaunchTarget.MCP_STDIO
            ),
            requests.map(DevLaunchRequest::target)
        )
        assertEquals(2, mcpAttempts)
        assertEquals(1, pauses)
    }

    @Test
    fun `mcp command fails when empty environment never becomes ready`() {
        val requests = mutableListOf<DevLaunchRequest>()
        var pauses = 0
        val launcher = DevLauncher { captured ->
            requests += captured
            if (captured.target == DevLaunchTarget.MCP_STDIO) 1 else 0
        }

        val result = Mcp(launcher, readinessAttempts = 3, readinessPause = { pauses++ }).test(
            listOf("--project-dir", projectDirectory.toString(), "--ensure-dev", "--allow-empty")
        )

        assertEquals(1, result.statusCode)
        assertTrue(result.output.contains("MCP adapter was not ready after 3 launch attempts"))
        assertTrue(result.output.contains("last exit code 1"))
        assertTrue(result.output.contains(".fluxzero/dev/bootstrap.log"))
        assertTrue(result.output.contains("fz dev status"))
        assertEquals(3, requests.count { it.target == DevLaunchTarget.MCP_STDIO })
        assertTrue(requests.none { it.target == DevLaunchTarget.CONTROL })
        assertEquals(2, pauses)
    }

    @Test
    fun `mcp command preserves interruption while waiting for empty environment`() {
        val requests = mutableListOf<DevLaunchRequest>()
        val launcher = DevLauncher { captured ->
            requests += captured
            if (captured.target == DevLaunchTarget.MCP_STDIO) 1 else 0
        }

        try {
            val result = Mcp(
                launcher,
                readinessAttempts = 3,
                readinessPause = { throw InterruptedException("test interruption") }
            ).test(
                listOf("--project-dir", projectDirectory.toString(), "--ensure-dev", "--allow-empty")
            )

            assertEquals(1, result.statusCode)
            assertTrue(result.output.contains("Interrupted while waiting for the empty Fluxzero dev environment to expose MCP"))
            assertTrue(Thread.currentThread().isInterrupted)
            assertEquals(listOf(DevLaunchTarget.SERVER, DevLaunchTarget.MCP_STDIO), requests.map(DevLaunchRequest::target))
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `mcp command does not retry an actionable adapter failure after the session declares MCP ready`() {
        val sessionDirectory = Files.createDirectories(projectDirectory.resolve(".fluxzero/dev"))
        Files.writeString(
            sessionDirectory.resolve("session.json"),
            """{"status":"running","mcp":{"name":"mcp","state":"running"}}"""
        )
        val requests = mutableListOf<DevLaunchRequest>()
        var pauses = 0
        val launcher = DevLauncher { captured ->
            requests += captured
            if (captured.target == DevLaunchTarget.MCP_STDIO) 1 else 0
        }

        val failure = assertFailsWith<IllegalStateException> {
            Mcp(launcher, readinessAttempts = 3, readinessPause = { pauses++ }).test(
                listOf("--project-dir", projectDirectory.toString(), "--ensure-dev", "--allow-empty")
            )
        }

        assertTrue(failure.message.orEmpty().contains("Fluxzero MCP adapter exited with code 1"))
        assertEquals(1, requests.count { it.target == DevLaunchTarget.MCP_STDIO })
        assertEquals(0, pauses)
    }

    @Test
    fun `mcp command does not retry non readiness exit codes`() {
        val requests = mutableListOf<DevLaunchRequest>()
        var pauses = 0
        val launcher = DevLauncher { captured ->
            requests += captured
            if (captured.target == DevLaunchTarget.MCP_STDIO) 2 else 0
        }

        val failure = assertFailsWith<IllegalStateException> {
            Mcp(launcher, readinessAttempts = 3, readinessPause = { pauses++ }).test(
                listOf("--project-dir", projectDirectory.toString(), "--ensure-dev", "--allow-empty")
            )
        }

        assertTrue(failure.message.orEmpty().contains("Fluxzero MCP adapter exited with code 2"))
        assertEquals(1, requests.count { it.target == DevLaunchTarget.MCP_STDIO })
        assertEquals(0, pauses)
    }

    @Test
    fun `mcp command treats interrupted adapter exits as terminal without retrying`() {
        listOf(130, 143).forEach { interruptedExitCode ->
            val requests = mutableListOf<DevLaunchRequest>()
            var pauses = 0
            val launcher = DevLauncher { captured ->
                requests += captured
                if (captured.target == DevLaunchTarget.MCP_STDIO) interruptedExitCode else 0
            }

            val result = Mcp(launcher, readinessAttempts = 3, readinessPause = { pauses++ }).test(
                listOf("--project-dir", projectDirectory.toString(), "--ensure-dev", "--allow-empty")
            )

            assertEquals(0, result.statusCode, "exit code $interruptedExitCode")
            assertEquals(1, requests.count { it.target == DevLaunchTarget.MCP_STDIO })
            assertEquals(0, pauses)
        }
    }

    @Test
    fun `mcp command preserves launcher interruption during MCP readiness`() {
        val requests = mutableListOf<DevLaunchRequest>()
        val launcher = DevLauncher { captured ->
            requests += captured
            if (captured.target == DevLaunchTarget.MCP_STDIO) throw InterruptedException("test interruption")
            0
        }

        try {
            val result = Mcp(launcher, readinessAttempts = 3, readinessPause = {}).test(
                listOf("--project-dir", projectDirectory.toString(), "--ensure-dev", "--allow-empty")
            )

            assertEquals(1, result.statusCode)
            assertTrue(result.output.contains("Interrupted while waiting for the empty Fluxzero dev environment to expose MCP"))
            assertTrue(Thread.currentThread().isInterrupted)
            assertEquals(listOf(DevLaunchTarget.SERVER, DevLaunchTarget.MCP_STDIO), requests.map(DevLaunchRequest::target))
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `mcp command preserves launcher interruption while starting the environment`() {
        val requests = mutableListOf<DevLaunchRequest>()
        val launcher = DevLauncher { captured ->
            requests += captured
            throw InterruptedException("test interruption")
        }

        try {
            val result = Mcp(launcher).test(
                listOf("--project-dir", projectDirectory.toString(), "--ensure-dev", "--allow-empty")
            )

            assertEquals(1, result.statusCode)
            assertTrue(result.output.contains("Interrupted while starting the Fluxzero dev environment"))
            assertTrue(Thread.currentThread().isInterrupted)
            assertEquals(listOf(DevLaunchTarget.SERVER), requests.map(DevLaunchRequest::target))
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `mcp command stops retrying when the readiness deadline elapses`() {
        val requests = mutableListOf<DevLaunchRequest>()
        var elapsedNanos = 0L
        var pauses = 0
        val launcher = DevLauncher { captured ->
            requests += captured
            if (captured.target == DevLaunchTarget.MCP_STDIO) {
                elapsedNanos += 6_000_000
                1
            } else {
                0
            }
        }

        val result = Mcp(
            launcher,
            readinessAttempts = 10,
            readinessPause = { pauses++; elapsedNanos += 5_000_000 },
            readinessTimeoutMillis = 10,
            monotonicNanos = { elapsedNanos }
        ).test(
            listOf("--project-dir", projectDirectory.toString(), "--ensure-dev", "--allow-empty")
        )

        assertEquals(1, result.statusCode)
        assertTrue(result.output.contains("10 ms retry window elapsed"))
        assertTrue(result.output.contains(".fluxzero/dev/bootstrap.log"))
        assertTrue(result.output.contains("fz dev status"))
        assertEquals(1, requests.count { it.target == DevLaunchTarget.MCP_STDIO })
        assertEquals(1, pauses)
    }

    @Test
    fun `mcp command keeps legacy ensure dev behavior outside empty mode`() {
        val requests = mutableListOf<DevLaunchRequest>()
        var pauses = 0
        val launcher = DevLauncher { captured ->
            requests += captured
            if (captured.target == DevLaunchTarget.MCP_STDIO) 1 else 0
        }

        val failure = assertFailsWith<IllegalStateException> {
            Mcp(launcher, readinessAttempts = 3, readinessPause = { pauses++ }).test(
                listOf("--project-dir", projectDirectory.toString(), "--ensure-dev")
            )
        }

        assertTrue(failure.message.orEmpty().contains("Fluxzero MCP adapter exited with code 1"))
        assertEquals(listOf(DevLaunchTarget.SERVER, DevLaunchTarget.MCP_STDIO), requests.map(DevLaunchRequest::target))
        assertEquals(0, pauses)
    }

    @Test
    fun `mcp command executor routes only startup output away from protocol stdout and delegates lifecycle`() {
        val executions = mutableListOf<Pair<List<String>, OutputMode>>()
        val cleanupExecutions = mutableListOf<Pair<List<String>, OutputMode>>()
        val lifecycle = mutableListOf<String>()
        var detachedOutput: Path? = null
        var releasedDirectory: Path? = null
        val delegate = object : CommandExecutor {
            override fun execute(command: List<String>, workingDirectory: Path, outputMode: OutputMode): Int {
                executions += command to outputMode
                return 17
            }

            override fun executeCleanup(command: List<String>, workingDirectory: Path, outputMode: OutputMode): Int {
                cleanupExecutions += command to outputMode
                return 18
            }

            override fun startDetached(command: List<String>, workingDirectory: Path, outputFile: Path): Long {
                detachedOutput = outputFile
                return 42
            }

            override fun releaseDetached(workingDirectory: Path) {
                releasedDirectory = workingDirectory
            }

            override fun <T> supervise(onShutdown: () -> Unit, action: () -> T): T {
                lifecycle += "single-start"
                onShutdown()
                return action().also { lifecycle += "single-end" }
            }

            override fun <T> supervise(
                onShutdownStarted: () -> Unit,
                onShutdownComplete: () -> Unit,
                action: () -> T
            ): T {
                lifecycle += "split-start"
                onShutdownStarted()
                return action().also {
                    onShutdownComplete()
                    lifecycle += "split-end"
                }
            }
        }
        val executor = McpCommandExecutor(delegate)
        val preflight = listOf("java", "-cp", "dev.jar", "io.fluxzero.devserver.DevServerPreflightMain")
        val wait = listOf("java", "-cp", "dev.jar", DevLaunchTarget.CONTROL.mainClass, "wait")
        val stop = listOf("java", "-cp", "dev.jar", DevLaunchTarget.CONTROL.mainClass, "stop")
        val status = listOf("java", "-cp", "dev.jar", DevLaunchTarget.CONTROL.mainClass, "status")
        val mcp = listOf("java", "-cp", "dev.jar", DevLaunchTarget.MCP_STDIO.mainClass)

        assertEquals(17, executor.execute(preflight, projectDirectory, OutputMode.INHERIT))
        assertEquals(17, executor.execute(wait, projectDirectory, OutputMode.INHERIT))
        assertEquals(17, executor.execute(stop, projectDirectory, OutputMode.INHERIT))
        assertEquals(17, executor.execute(status, projectDirectory, OutputMode.INHERIT))
        assertEquals(17, executor.execute(mcp, projectDirectory, OutputMode.INHERIT))
        assertEquals(17, executor.execute(wait, projectDirectory, OutputMode.DISCARD))
        assertEquals(18, executor.executeCleanup(stop, projectDirectory, OutputMode.INHERIT))

        assertEquals(
            listOf(
                OutputMode.STDOUT_TO_STDERR,
                OutputMode.STDOUT_TO_STDERR,
                OutputMode.STDOUT_TO_STDERR,
                OutputMode.INHERIT,
                OutputMode.INHERIT,
                OutputMode.DISCARD
            ),
            executions.map { it.second }
        )
        assertEquals(listOf(OutputMode.STDOUT_TO_STDERR), cleanupExecutions.map { it.second })
        val detachedLog = projectDirectory.resolve("bootstrap.log")
        assertEquals(42, executor.startDetached(listOf("server"), projectDirectory, detachedLog))
        assertEquals(detachedLog, detachedOutput)
        executor.releaseDetached(projectDirectory)
        assertEquals(projectDirectory, releasedDirectory)
        assertEquals("single-result", executor.supervise({ lifecycle += "single-shutdown" }) {
            lifecycle += "single-action"
            "single-result"
        })
        assertEquals("split-result", executor.supervise(
            onShutdownStarted = { lifecycle += "split-shutdown-start" },
            onShutdownComplete = { lifecycle += "split-shutdown-complete" }
        ) {
            lifecycle += "split-action"
            "split-result"
        })
        assertEquals(
            listOf(
                "single-start", "single-shutdown", "single-action", "single-end",
                "split-start", "split-shutdown-start", "split-action", "split-shutdown-complete", "split-end"
            ),
            lifecycle
        )
    }

    @Test
    fun `mcp command rejects allow empty without ensure dev`() {
        val requests = mutableListOf<DevLaunchRequest>()
        val launcher = DevLauncher { captured -> requests += captured; 0 }

        val result = Mcp(launcher).test(
            listOf("--project-dir", projectDirectory.toString(), "--allow-empty")
        )

        assertEquals(1, result.statusCode)
        assertTrue(result.output.contains("--allow-empty requires --ensure-dev"))
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `empty directory MCP process initializes and lists tools end to end`() {
        val cliJarValue = System.getenv("FLUXZERO_CLI_E2E_JAR")
        val devServerVersion = System.getenv("FLUXZERO_MCP_E2E_DEV_SERVER_VERSION")
        assumeTrue(
            !cliJarValue.isNullOrBlank() && !devServerVersion.isNullOrBlank(),
            "Set FLUXZERO_CLI_E2E_JAR and FLUXZERO_MCP_E2E_DEV_SERVER_VERSION to run the process smoke."
        )
        val cliJar = Path.of(requireNotNull(cliJarValue)).toAbsolutePath().normalize()
        assumeTrue(Files.isRegularFile(cliJar), "FLUXZERO_CLI_E2E_JAR must identify a runnable CLI JAR.")
        Files.list(projectDirectory).use { entries -> assertEquals(0, entries.count()) }
        val stderrFile = Files.createTempFile(projectDirectory.parent, "fluxzero-empty-mcp-", ".stderr")
        val command = listOf(
            javaExecutable(),
            "-jar", cliJar.toString(),
            "mcp",
            "--project-dir", projectDirectory.toString(),
            "--dev-server-version", requireNotNull(devServerVersion),
            "--ensure-dev",
            "--allow-empty"
        )
        var process: Process? = null
        try {
            process = ProcessBuilder(command)
                .redirectError(stderrFile.toFile())
                .start()
            val reader = process.inputStream.bufferedReader()
            val writer = process.outputStream.bufferedWriter()
            writer.write(
                """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"empty-directory-test","version":"1"}}}"""
            )
            writer.newLine()
            writer.flush()
            val initialize = readProtocolResponse(process, reader, 1, stderrFile)
            assertTrue(initialize.contains("\"name\":\"fluxzero-dev-stdio\""), initialize)

            writer.write("""{"jsonrpc":"2.0","method":"notifications/initialized","params":{}}""")
            writer.newLine()
            writer.write("""{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}""")
            writer.newLine()
            writer.flush()
            val tools = readProtocolResponse(process, reader, 2, stderrFile)
            assertTrue(tools.contains("\"tools\":["), tools)
            assertTrue(tools.contains("\"name\":\"get_status\""), tools)
            assertTrue(Files.isDirectory(projectDirectory.resolve(".fluxzero/dev")))
            assertTrue(
                listOf("pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts")
                    .none { Files.exists(projectDirectory.resolve(it)) }
            )
        } finally {
            process?.outputStream?.runCatching { close() }
            process?.destroy()
            if (process?.waitFor(5, TimeUnit.SECONDS) == false) {
                process.destroyForcibly()
                process.waitFor(5, TimeUnit.SECONDS)
            }
            stopProcess(cliJar, requireNotNull(devServerVersion), projectDirectory)
            Files.deleteIfExists(stderrFile)
        }
    }

    @Test
    fun `forwards backend only override`() {
        mavenProject()
        var request: DevLaunchRequest? = null
        val launcher = DevLauncher { captured -> request = captured; 0 }

        val result = Dev(launcher).test(
            listOf("--project-dir", projectDirectory.toString(), "--no-frontend")
        )

        assertEquals(0, result.statusCode)
        assertTrue(request!!.arguments.contains("--no-frontend"))
    }

    @Test
    fun `starts dev server detached when background is requested`() {
        mavenProject()
        var request: DevLaunchRequest? = null
        val launcher = DevLauncher { captured -> request = captured; 0 }

        val result = Dev(launcher).test(
            listOf("--project-dir", projectDirectory.toString(), "--background")
        )

        assertEquals(0, result.statusCode)
        assertEquals(DevLaunchTarget.SERVER, request?.target)
        assertTrue(request?.detached == true)
    }

    @Test
    fun `restart stops the current environment and starts a fresh detached environment`() {
        mavenProject()
        val requests = mutableListOf<DevLaunchRequest>()
        val launcher = DevLauncher { captured -> requests += captured; 0 }

        val result = Dev(launcher).test(
            listOf(
                "restart",
                "--project-dir", projectDirectory.toString(),
                "--dev-server-version", "1.6.5",
                "--profile", "dashboard-auditlog",
                "--port", "4200",
                "--force"
            )
        )

        assertEquals(0, result.statusCode)
        assertEquals(2, requests.size)
        assertEquals(DevLaunchTarget.CONTROL, requests[0].target)
        assertEquals("1.6.5", requests[0].devServerVersion)
        assertTrue(requests[0].arguments.containsAll(listOf("stop", "--force")))
        assertEquals(DevLaunchTarget.SERVER, requests[1].target)
        assertEquals("1.6.5", requests[1].devServerVersion)
        assertTrue(requests[1].detached)
        assertTrue(requests[1].arguments.containsAll(listOf("--profile", "dashboard-auditlog", "--port", "4200")))
    }

    @Test
    fun `restart does not start a new environment when stopping fails`() {
        mavenProject()
        val requests = mutableListOf<DevLaunchRequest>()
        val launcher = DevLauncher { captured ->
            requests += captured
            17
        }

        val result = Dev(launcher).test(
            listOf("restart", "--project-dir", projectDirectory.toString())
        )

        assertEquals(17, result.statusCode)
        assertEquals(1, requests.size)
        assertEquals(DevLaunchTarget.CONTROL, requests.single().target)
    }

    @Test
    fun `rejects start outside a Maven or Gradle project before launching`() {
        var launched = false
        val result = Dev({ launched = true; 0 }, DevProjectInitializer { null }).test(
            listOf("--project-dir", projectDirectory.toString())
        )

        assertEquals(1, result.statusCode)
        assertTrue(!launched)
        assertTrue(result.output.contains("No project was created"))
        assertTrue(result.output.contains("fz init"))
    }

    @Test
    fun `starts generated project returned by interactive initializer`() {
        val generated = Files.createDirectory(projectDirectory.resolve("generated"))
        Files.writeString(generated.resolve("pom.xml"), "<project/>")
        var initialized: Path? = null
        var request: DevLaunchRequest? = null
        val result = Dev(
            DevLauncher { captured -> request = captured; 0 },
            DevProjectInitializer { directory -> initialized = directory; generated }
        ).test(listOf("--project-dir", projectDirectory.toString()))

        assertEquals(0, result.statusCode)
        assertEquals(projectDirectory, initialized)
        assertEquals(generated.toAbsolutePath(), request?.projectDirectory)
        assertTrue(request!!.arguments.containsAll(listOf("--project-dir", generated.toAbsolutePath().toString())))
    }

    @Test
    fun `forwards lifecycle actions to control target`() {
        var request: DevLaunchRequest? = null
        val launcher = DevLauncher { captured -> request = captured; 0 }

        val result = Dev(launcher).test(
            listOf("logs", "--project-dir", projectDirectory.toString(), "--follow", "--errors", "--app", "orders")
        )

        assertEquals(0, result.statusCode)
        assertEquals(DevLaunchTarget.CONTROL, request?.target)
        assertTrue(request!!.arguments.containsAll(listOf("logs", "--follow", "--errors", "--app", "orders")))
    }

    @Test
    fun `forwards global list action outside a build project`() {
        var request: DevLaunchRequest? = null
        val launcher = DevLauncher { captured -> request = captured; 0 }

        val result = Dev(launcher).test(
            listOf("list", "--project-dir", projectDirectory.toString(), "--json")
        )

        assertEquals(0, result.statusCode)
        assertEquals(DevLaunchTarget.CONTROL, request?.target)
        assertTrue(request!!.arguments.containsAll(listOf("list", "--json")))
    }

    @Test
    fun `forwards global stop action outside a build project`() {
        var request: DevLaunchRequest? = null
        val launcher = DevLauncher { captured -> request = captured; 0 }

        val result = Dev(launcher).test(
            listOf("stop", "--project-dir", projectDirectory.toString(), "--all", "--force")
        )

        assertEquals(0, result.statusCode)
        assertEquals(DevLaunchTarget.CONTROL, request?.target)
        assertTrue(request!!.arguments.containsAll(listOf("stop", "--all", "--force")))
    }

    @Test
    fun `forwards attach action to control target`() {
        var request: DevLaunchRequest? = null
        val launcher = DevLauncher { captured -> request = captured; 0 }

        val result = Dev(launcher).test(
            listOf("attach", "--project-dir", projectDirectory.toString())
        )

        assertEquals(0, result.statusCode)
        assertEquals(DevLaunchTarget.CONTROL, request?.target)
        assertTrue(request!!.arguments.contains("attach"))
    }

    @Test
    fun `prints version aligned project configuration through config target`() {
        var request: DevLaunchRequest? = null
        val launcher = DevLauncher { captured -> request = captured; 0 }

        val result = Dev(launcher).test(
            listOf("config", "--project-dir", projectDirectory.toString(), "--dev-server-version", "1.2.3")
        )

        assertEquals(0, result.statusCode)
        assertEquals(DevLaunchTarget.CONFIG, request?.target)
        assertEquals("1.2.3", request?.devServerVersion)
        assertTrue(request?.arguments?.isEmpty() == true)
    }

    @Test
    fun `help points agents to project configuration reference`() {
        val result = Dev(DevLauncher { 0 }).test("--help")

        assertEquals(0, result.statusCode)
        assertTrue(result.output.contains("fz dev config"))
        assertTrue(result.output.contains(".fluxzero/dev.yaml"))
    }

    private fun readProtocolResponse(
        process: Process,
        reader: BufferedReader,
        id: Int,
        stderrFile: Path
    ): String {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45)
        val output = mutableListOf<String>()
        while (System.nanoTime() < deadline) {
            while (reader.ready()) {
                val line = reader.readLine() ?: break
                output += line
                val protocolLine = line.trim()
                check(
                    protocolLine.startsWith("{") && protocolLine.endsWith("}") &&
                        protocolLine.contains("\"jsonrpc\":\"2.0\"")
                ) {
                    "MCP command wrote non-JSON-RPC data to protocol stdout: '$line'. stderr=${Files.readString(stderrFile)}"
                }
                if (protocolLine.contains("\"id\":$id")) return protocolLine
            }
            check(process.isAlive) {
                "MCP process exited with code ${process.exitValue()}. stdout=$output stderr=${Files.readString(stderrFile)}"
            }
            Thread.sleep(25)
        }
        error("Timed out waiting for MCP response $id. stdout=$output stderr=${Files.readString(stderrFile)}")
    }

    private fun stopProcess(cliJar: Path, devServerVersion: String, projectDirectory: Path) {
        val stop = ProcessBuilder(
            javaExecutable(),
            "-jar", cliJar.toString(),
            "dev", "stop",
            "--project-dir", projectDirectory.toString(),
            "--dev-server-version", devServerVersion,
            "--force"
        ).redirectErrorStream(true).start()
        if (!stop.waitFor(20, TimeUnit.SECONDS)) {
            stop.destroyForcibly()
            stop.waitFor(5, TimeUnit.SECONDS)
        }
        check(stop.exitValue() == 0) {
            "Could not stop empty-directory smoke environment: ${stop.inputStream.bufferedReader().readText()}"
        }
    }

    private fun javaExecutable(): String = Path.of(
        System.getProperty("java.home"),
        "bin",
        if (System.getProperty("os.name").lowercase().contains("windows")) "java.exe" else "java"
    ).toString()
}
