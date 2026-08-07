package host.flux.dev

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DevServerLauncherTest {
    @TempDir
    lateinit var projectDirectory: Path

    @Test
    fun `resolves with project wrapper and forwards main class and arguments`() {
        Files.writeString(projectDirectory.resolve("pom.xml"), "<project/>")
        Files.writeString(projectDirectory.resolve("mvnw"), "wrapper")
        val dependency = Files.createFile(projectDirectory.resolve("dev-server.jar"))
        val commands = mutableListOf<Invocation>()
        var serverCommand = emptyList<String>()
        val executor = object : CommandExecutor {
            override fun execute(command: List<String>, workingDirectory: Path, outputMode: OutputMode): Int {
                commands += Invocation(command, workingDirectory, outputMode)
                command.firstOrNull { it.startsWith("-Dmdep.outputFile=") }?.let {
                    Files.writeString(Path.of(it.substringAfter('=')), dependency.toString())
                }
                return 0
            }

            override fun startDetached(command: List<String>, workingDirectory: Path, outputFile: Path): Long {
                serverCommand = command
                return 4242
            }
        }
        val launcher = DevServerLauncher(executor, mapOf("JAVA_HOME" to projectDirectory.toString())) { }

        val exitCode = launcher.launch(
            DevLaunchRequest(
                projectDirectory,
                "0-SNAPSHOT",
                DevLaunchTarget.SERVER,
                listOf("--project-dir", projectDirectory.toString(), "--fast-compiler")
            )
        )

        assertEquals(0, exitCode)
        assertEquals(3, commands.size)
        assertEquals(OutputMode.STDOUT_TO_STDERR, commands[0].outputMode)
        assertEquals(projectDirectory.resolve("mvnw").toAbsolutePath().toString(), commands[0].command.first())
        val launcherPom = Files.readString(projectDirectory.resolve(".fluxzero/dev/launcher/pom.xml"))
        assertTrue(launcherPom.contains("<classifier>standalone</classifier>"))
        assertTrue(launcherPom.contains("<groupId>*</groupId>"))
        assertTrue(commands[1].command.contains("io.fluxzero.devserver.DevServerPreflightMain"))
        assertEquals(OutputMode.INHERIT, commands[2].outputMode)
        assertTrue(serverCommand.contains("--enable-native-access=ALL-UNNAMED"))
        if (Runtime.version().feature() >= 24) {
            assertTrue(serverCommand.contains("--sun-misc-unsafe-memory-access=allow"))
        }
        assertTrue(!serverCommand.contains("-Dfluxzero.dev.launcherOwnsShutdown=true"))
        assertTrue(serverCommand.contains(DevLaunchTarget.SERVER.mainClass))
        assertTrue(serverCommand.contains("--fast-compiler"))
        assertTrue(commands[2].command.containsAll(listOf(DevLaunchTarget.CONTROL.mainClass, "attach", "--pid", "4242")))
    }

    @Test
    fun `reuses a valid project-local classpath cache`() {
        Files.writeString(projectDirectory.resolve("pom.xml"), "<project/>")
        val launcherDirectory = Files.createDirectories(projectDirectory.resolve(".fluxzero/dev/launcher"))
        val dependency = Files.createFile(projectDirectory.resolve("dev-server.jar"))
        Files.writeString(launcherDirectory.resolve("classpath.txt"), dependency.toString())
        Files.writeString(launcherDirectory.resolve("version"), "1.2.3")
        val commands = mutableListOf<List<String>>()
        val executor = CommandExecutor { command, _, _ -> commands += command; 0 }

        DevServerLauncher(executor, emptyMap()) { }.launch(
            DevLaunchRequest(projectDirectory, "1.2.3", DevLaunchTarget.MCP_STDIO)
        )

        assertEquals(1, commands.size)
        assertTrue(commands.single().contains(DevLaunchTarget.MCP_STDIO.mainClass))
    }

    @Test
    fun `new session resolves the latest compatible stable release`() {
        val executor = CommandExecutor { _, _, _ -> 0 }
        val resolver = DevServerVersionResolver({
            "<metadata><versioning><versions><version>1.6.2</version></versions></versioning></metadata>"
        }, projectDirectory.resolve("metadata-cache")) { }
        val bytes = "dev-server".encodeToByteArray()
        val artifacts = DevServerArtifactCache(projectDirectory.resolve("artifact-cache"), { uri ->
            if (uri.toString().endsWith(".sha256")) sha256(bytes).encodeToByteArray() else bytes
        }) { }
        val classpathResolver = DevServerClasspathResolver(executor, artifacts) { }

        DevServerLauncher(
            executor, emptyMap(), versionResolver = resolver, classpathResolver = classpathResolver
        ) { }.launch(
            DevLaunchRequest(projectDirectory, target = DevLaunchTarget.CONTROL, arguments = listOf("status"))
        )

        assertEquals("1.6.2", Files.readString(projectDirectory.resolve(".fluxzero/dev/launcher/version")))
        assertEquals("1.6.2", classpathResolver.resolvedVersion(projectDirectory))
    }

    @Test
    fun `configuration reference resolves latest compatible release instead of project pin`() {
        val launcherDirectory = Files.createDirectories(projectDirectory.resolve(".fluxzero/dev/launcher"))
        val pinnedDependency = Files.createFile(projectDirectory.resolve("dev-server-pinned.jar"))
        Files.writeString(launcherDirectory.resolve("classpath.txt"), pinnedDependency.toString())
        Files.writeString(launcherDirectory.resolve("version"), "1.2.3")
        val commands = mutableListOf<List<String>>()
        val executor = CommandExecutor { command, _, _ -> commands += command; 0 }
        val resolver = DevServerVersionResolver({
            "<metadata><versioning><versions><version>1.6.2</version></versions></versioning></metadata>"
        }, projectDirectory.resolve("metadata-cache")) { }
        val bytes = "latest-dev-server".encodeToByteArray()
        val artifacts = DevServerArtifactCache(projectDirectory.resolve("artifact-cache"), { uri ->
            if (uri.toString().endsWith(".sha256")) sha256(bytes).encodeToByteArray() else bytes
        }) { }
        val classpathResolver = DevServerClasspathResolver(executor, artifacts) { }

        DevServerLauncher(
            executor, emptyMap(), versionResolver = resolver, classpathResolver = classpathResolver
        ) { }.launch(
            DevLaunchRequest(projectDirectory, target = DevLaunchTarget.CONFIG)
        )

        assertEquals("1.2.3", Files.readString(launcherDirectory.resolve("version")))
        assertEquals(
            "1.6.2",
            Files.readString(projectDirectory.resolve(".fluxzero/dev/config-launcher/version"))
        )
        assertTrue(commands.single().contains(DevLaunchTarget.CONFIG.mainClass))
    }

    @Test
    fun `global control actions resolve latest compatible release instead of project pin`() {
        val launcherDirectory = Files.createDirectories(projectDirectory.resolve(".fluxzero/dev/launcher"))
        val pinnedDependency = Files.createFile(projectDirectory.resolve("dev-server-pinned.jar"))
        Files.writeString(launcherDirectory.resolve("classpath.txt"), pinnedDependency.toString())
        Files.writeString(launcherDirectory.resolve("version"), "1.2.0")
        val commands = mutableListOf<List<String>>()
        val executor = CommandExecutor { command, _, _ -> commands += command; 0 }
        val resolver = DevServerVersionResolver({
            "<metadata><versioning><versions><version>1.2.3</version></versions></versioning></metadata>"
        }, projectDirectory.resolve("metadata-cache")) { }
        val bytes = "latest-dev-server".encodeToByteArray()
        val artifacts = DevServerArtifactCache(projectDirectory.resolve("artifact-cache"), { uri ->
            if (uri.toString().endsWith(".sha256")) sha256(bytes).encodeToByteArray() else bytes
        }) { }
        val classpathResolver = DevServerClasspathResolver(executor, artifacts) { }
        val launcher = DevServerLauncher(
            executor, emptyMap(), versionResolver = resolver, classpathResolver = classpathResolver
        ) { }

        launcher.launch(DevLaunchRequest(
            projectDirectory, target = DevLaunchTarget.CONTROL, arguments = listOf("list")
        ))
        launcher.launch(DevLaunchRequest(
            projectDirectory, target = DevLaunchTarget.CONTROL, arguments = listOf("stop", "--all")
        ))

        assertEquals("1.2.0", Files.readString(launcherDirectory.resolve("version")))
        assertEquals(
            "1.2.3",
            Files.readString(projectDirectory.resolve(".fluxzero/dev/config-launcher/version"))
        )
        assertEquals(2, commands.size)
        assertTrue(commands.all { it.contains(DevLaunchTarget.CONTROL.mainClass) })
        assertTrue(commands.none { it.contains(pinnedDependency.toString()) })
        assertTrue(commands[0].contains("list"))
        assertTrue(commands[1].containsAll(listOf("stop", "--all")))
    }

    @Test
    fun `refreshes snapshot classpath instead of reusing the cache`() {
        Files.writeString(projectDirectory.resolve("pom.xml"), "<project/>")
        val launcherDirectory = Files.createDirectories(projectDirectory.resolve(".fluxzero/dev/launcher"))
        val dependency = Files.createFile(projectDirectory.resolve("dev-server.jar"))
        Files.writeString(launcherDirectory.resolve("classpath.txt"), dependency.toString())
        Files.writeString(launcherDirectory.resolve("version"), "0-SNAPSHOT")
        val commands = mutableListOf<List<String>>()
        val executor = object : CommandExecutor {
            override fun execute(command: List<String>, workingDirectory: Path, outputMode: OutputMode): Int {
                commands += command
                command.firstOrNull { it.startsWith("-Dmdep.outputFile=") }?.let {
                    Files.writeString(Path.of(it.substringAfter('=')), dependency.toString())
                }
                return 0
            }

            override fun startDetached(command: List<String>, workingDirectory: Path, outputFile: Path) = 4242L
        }

        DevServerLauncher(executor, emptyMap()) { }.launch(
            DevLaunchRequest(projectDirectory, "0-SNAPSHOT", DevLaunchTarget.SERVER)
        )

        assertEquals(3, commands.size)
        assertTrue(commands.first().any { it.contains("maven-dependency-plugin") })
        assertTrue(commands.last().containsAll(listOf(DevLaunchTarget.CONTROL.mainClass, "attach")))
    }

    @Test
    fun `control action reuses active snapshot classpath`() {
        Files.writeString(projectDirectory.resolve("pom.xml"), "<project/>")
        val launcherDirectory = Files.createDirectories(projectDirectory.resolve(".fluxzero/dev/launcher"))
        val dependency = Files.createFile(projectDirectory.resolve("dev-server.jar"))
        Files.writeString(launcherDirectory.resolve("classpath.txt"), dependency.toString())
        Files.writeString(launcherDirectory.resolve("version"), "0-SNAPSHOT")
        val commands = mutableListOf<List<String>>()
        val executor = CommandExecutor { command, _, _ -> commands += command; 0 }

        DevServerLauncher(executor, emptyMap()) { }.launch(
            DevLaunchRequest(projectDirectory, "0-SNAPSHOT", DevLaunchTarget.CONTROL, listOf("status"))
        )

        assertEquals(1, commands.size)
        assertTrue(commands.single().contains(DevLaunchTarget.CONTROL.mainClass))
    }

    @Test
    fun `bare dev attaches to an existing project session`() {
        Files.writeString(projectDirectory.resolve("pom.xml"), "<project/>")
        val launcherDirectory = Files.createDirectories(projectDirectory.resolve(".fluxzero/dev/launcher"))
        val dependency = Files.createFile(projectDirectory.resolve("dev-server.jar"))
        Files.writeString(launcherDirectory.resolve("classpath.txt"), dependency.toString())
        Files.writeString(launcherDirectory.resolve("version"), "1.2.3")
        Files.writeString(projectDirectory.resolve(".fluxzero/dev/session.json"),
                          """{"status":"running","pid":${ProcessHandle.current().pid()}}""")
        val commands = mutableListOf<Invocation>()
        val executor = object : CommandExecutor {
            override fun execute(command: List<String>, workingDirectory: Path, outputMode: OutputMode): Int {
                commands += Invocation(command, workingDirectory, outputMode)
                return 0
            }

            override fun startDetached(command: List<String>, workingDirectory: Path, outputFile: Path): Long {
                error("existing session must not start a second server")
            }
        }

        val resolver = DevServerVersionResolver({ error("an active session must not check for updates") }) { }
        val exitCode = DevServerLauncher(executor, emptyMap(), versionResolver = resolver) { }.launch(
            DevLaunchRequest(projectDirectory, target = DevLaunchTarget.SERVER)
        )

        assertEquals(0, exitCode)
        assertEquals(OutputMode.DISCARD, commands.first().outputMode)
        assertTrue(commands.first().command.contains("probe"))
        assertTrue(commands.any { it.command.contains("attach") })
    }

    @Test
    fun `control action uses version pinned by active launcher`() {
        Files.writeString(projectDirectory.resolve("pom.xml"), "<project/>")
        val launcherDirectory = Files.createDirectories(projectDirectory.resolve(".fluxzero/dev/launcher"))
        val dependency = Files.createFile(projectDirectory.resolve("dev-server.jar"))
        Files.writeString(launcherDirectory.resolve("classpath.txt"), dependency.toString())
        Files.writeString(launcherDirectory.resolve("version"), "1.2.3")
        Files.writeString(projectDirectory.resolve(".fluxzero/dev/session.json"),
                          """{"status":"running","pid":${ProcessHandle.current().pid()}}""")
        val commands = mutableListOf<List<String>>()
        val executor = CommandExecutor { command, _, _ -> commands += command; 0 }
        val resolver = DevServerVersionResolver({ error("an active session must not check for updates") }) { }

        DevServerLauncher(executor, emptyMap(), versionResolver = resolver) { }.launch(
            DevLaunchRequest(projectDirectory, "1.9.0", DevLaunchTarget.CONTROL, listOf("status"))
        )

        assertEquals(1, commands.size)
        assertTrue(commands.single().contains(DevLaunchTarget.CONTROL.mainClass))
        assertEquals("1.2.3", Files.readString(launcherDirectory.resolve("version")))
    }

    @Test
    fun `reports one clean stop when snapshot resolution is interrupted`() {
        Files.writeString(projectDirectory.resolve("pom.xml"), "<project/>")
        val messages = mutableListOf<String>()
        val launcher = DevServerLauncher(CommandExecutor { _, _, _ -> 130 }, emptyMap(), messageSink = messages::add)

        val exitCode = launcher.launch(
            DevLaunchRequest(projectDirectory, "0-SNAPSHOT", DevLaunchTarget.SERVER)
        )

        assertEquals(130, exitCode)
        assertCleanStop(messages)
    }

    @Test
    fun `shutdown callback and signal exit converge on one outcome`() {
        Files.writeString(projectDirectory.resolve("pom.xml"), "<project/>")
        val dependency = Files.createFile(projectDirectory.resolve("dev-server.jar"))
        val messages = mutableListOf<String>()
        val commands = mutableListOf<Invocation>()
        var invocation = 0
        lateinit var shutdown: () -> Unit
        val executor = object : CommandExecutor {
            override fun execute(command: List<String>, workingDirectory: Path, outputMode: OutputMode): Int {
                commands += Invocation(command, workingDirectory, outputMode)
                invocation++
                if (invocation == 1) {
                    command.first { it.startsWith("-Dmdep.outputFile=") }.let {
                        Files.writeString(Path.of(it.substringAfter('=')), dependency.toString())
                    }
                    return 0
                }
                if (command.contains("io.fluxzero.devserver.DevServerPreflightMain")) {
                    return 0
                }
                shutdown()
                return 130
            }

            override fun <T> supervise(onShutdown: () -> Unit, action: () -> T): T {
                shutdown = onShutdown
                return action()
            }

            override fun startDetached(command: List<String>, workingDirectory: Path, outputFile: Path) = 4242L
        }

        val exitCode = DevServerLauncher(executor, emptyMap(), messageSink = messages::add).launch(
            DevLaunchRequest(projectDirectory, "0-SNAPSHOT", DevLaunchTarget.SERVER)
        )

        assertEquals(0, exitCode)
        assertCleanStop(messages)
        assertTrue(messages.none { it.contains("continues in the background") })
        assertTrue(commands.any {
            it.outputMode == OutputMode.DISCARD
                && it.command.containsAll(listOf(DevLaunchTarget.CONTROL.mainClass, "stop"))
                && !it.command.contains("--force")
        })
    }

    @Test
    fun `interrupting a bare attach to an active session stops the environment`() {
        Files.writeString(projectDirectory.resolve("pom.xml"), "<project/>")
        val launcherDirectory = Files.createDirectories(projectDirectory.resolve(".fluxzero/dev/launcher"))
        val dependency = Files.createFile(projectDirectory.resolve("dev-server.jar"))
        Files.writeString(launcherDirectory.resolve("classpath.txt"), dependency.toString())
        Files.writeString(launcherDirectory.resolve("version"), "1.2.3")
        Files.writeString(projectDirectory.resolve(".fluxzero/dev/session.json"),
                          """{"status":"running","pid":${ProcessHandle.current().pid()}}""")
        val commands = mutableListOf<Invocation>()
        val messages = mutableListOf<String>()
        val executor = object : CommandExecutor {
            override fun execute(command: List<String>, workingDirectory: Path, outputMode: OutputMode): Int {
                commands += Invocation(command, workingDirectory, outputMode)
                return when {
                    command.contains("probe") -> 0
                    command.contains("attach") -> 130
                    command.contains("stop") -> 0
                    else -> error("unexpected command: $command")
                }
            }

            override fun startDetached(command: List<String>, workingDirectory: Path, outputFile: Path): Long {
                error("existing session must not start a second server")
            }
        }

        val exitCode = DevServerLauncher(executor, emptyMap(), messageSink = messages::add).launch(
            DevLaunchRequest(projectDirectory, "1.2.3", DevLaunchTarget.SERVER)
        )

        assertEquals(0, exitCode)
        assertTrue(commands.any { it.command.contains("attach") })
        assertTrue(commands.any { it.command.contains("stop") && !it.command.contains("--force") })
        assertCleanStop(messages)
    }

    @Test
    fun `interrupting an explicit attach stops the environment`() {
        Files.writeString(projectDirectory.resolve("pom.xml"), "<project/>")
        val launcherDirectory = Files.createDirectories(projectDirectory.resolve(".fluxzero/dev/launcher"))
        val dependency = Files.createFile(projectDirectory.resolve("dev-server.jar"))
        Files.writeString(launcherDirectory.resolve("classpath.txt"), dependency.toString())
        Files.writeString(launcherDirectory.resolve("version"), "1.2.3")
        val commands = mutableListOf<Invocation>()
        val messages = mutableListOf<String>()
        val executor = CommandExecutor { command, workingDirectory, outputMode ->
            commands += Invocation(command, workingDirectory, outputMode)
            if (command.contains("attach")) 130 else 0
        }

        val exitCode = DevServerLauncher(executor, emptyMap(), messageSink = messages::add).launch(
            DevLaunchRequest(
                projectDirectory, "1.2.3", DevLaunchTarget.CONTROL,
                listOf("attach", "--project-dir", projectDirectory.toString())
            )
        )

        assertEquals(0, exitCode)
        assertTrue(commands.any { it.command.contains("stop") && !it.command.contains("--force") })
        assertCleanStop(messages)
    }

    @Test
    fun `one supervision scope covers resolver and server launch`() {
        Files.writeString(projectDirectory.resolve("pom.xml"), "<project/>")
        val dependency = Files.createFile(projectDirectory.resolve("dev-server.jar"))
        val events = mutableListOf<String>()
        val executor = object : CommandExecutor {
            override fun execute(command: List<String>, workingDirectory: Path, outputMode: OutputMode): Int {
                if (command.any { it.startsWith("-Dmdep.outputFile=") }) {
                    events += "resolve"
                    command.first { it.startsWith("-Dmdep.outputFile=") }.let {
                        Files.writeString(Path.of(it.substringAfter('=')), dependency.toString())
                    }
                } else if (command.contains("io.fluxzero.devserver.DevServerPreflightMain")) {
                    events += "preflight"
                } else {
                    events += "attach"
                }
                return 0
            }

            override fun startDetached(command: List<String>, workingDirectory: Path, outputFile: Path): Long {
                events += "server"
                return 4242
            }

            override fun <T> supervise(onShutdown: () -> Unit, action: () -> T): T {
                events += "supervise-start"
                return try {
                    action()
                } finally {
                    events += "supervise-end"
                }
            }
        }

        DevServerLauncher(executor, emptyMap()) { }.launch(
            DevLaunchRequest(projectDirectory, "0-SNAPSHOT", DevLaunchTarget.SERVER)
        )

        assertEquals(listOf("supervise-start", "resolve", "preflight", "server", "attach", "supervise-end"), events)
    }

    @Test
    fun `detached launch starts server without terminal ownership and waits for readiness`() {
        Files.writeString(projectDirectory.resolve("pom.xml"), "<project/>")
        val dependency = Files.createFile(projectDirectory.resolve("dev-server.jar"))
        val commands = mutableListOf<Invocation>()
        var detachedCommand: List<String>? = null
        val executor = object : CommandExecutor {
            override fun execute(command: List<String>, workingDirectory: Path, outputMode: OutputMode): Int {
                commands += Invocation(command, workingDirectory, outputMode)
                command.firstOrNull { it.startsWith("-Dmdep.outputFile=") }?.let {
                    Files.writeString(Path.of(it.substringAfter('=')), dependency.toString())
                }
                return 0
            }

            override fun startDetached(command: List<String>, workingDirectory: Path, outputFile: Path): Long {
                detachedCommand = command
                assertEquals(projectDirectory.resolve(".fluxzero/dev/bootstrap.log"), outputFile)
                return 4242
            }
        }

        val exitCode = DevServerLauncher(executor, emptyMap()) { }.launch(
            DevLaunchRequest(
                projectDirectory, "0-SNAPSHOT", DevLaunchTarget.SERVER,
                listOf("--project-dir", projectDirectory.toString()), detached = true
            )
        )

        assertEquals(0, exitCode)
        assertTrue(detachedCommand!!.contains(DevLaunchTarget.SERVER.mainClass))
        assertTrue(!detachedCommand!!.contains("-Dfluxzero.dev.launcherOwnsShutdown=true"))
        val wait = commands.last().command
        assertTrue(wait.contains(DevLaunchTarget.CONTROL.mainClass))
        assertTrue(wait.containsAll(listOf("wait", "--pid", "4242")))
        assertTrue(wait.none { it == "-Dfluxzero.dev.control.agentReady=true" })
    }

    @Test
    fun `agent launch waits only for the dev control plane`() {
        Files.writeString(projectDirectory.resolve("pom.xml"), "<project/>")
        val dependency = Files.createFile(projectDirectory.resolve("dev-server.jar"))
        val commands = mutableListOf<Invocation>()
        val executor = object : CommandExecutor {
            override fun execute(command: List<String>, workingDirectory: Path, outputMode: OutputMode): Int {
                commands += Invocation(command, workingDirectory, outputMode)
                command.firstOrNull { it.startsWith("-Dmdep.outputFile=") }?.let {
                    Files.writeString(Path.of(it.substringAfter('=')), dependency.toString())
                }
                return 0
            }

            override fun startDetached(command: List<String>, workingDirectory: Path, outputFile: Path): Long = 4242
        }

        val exitCode = DevServerLauncher(executor, emptyMap()) { }.launch(
            DevLaunchRequest(
                projectDirectory, "0-SNAPSHOT", DevLaunchTarget.SERVER,
                listOf("--project-dir", projectDirectory.toString()), detached = true,
                startupReadiness = DevStartupReadiness.AGENT_CONTROL_PLANE
            )
        )

        assertEquals(0, exitCode)
        val wait = commands.last().command
        assertTrue(wait.contains(DevLaunchTarget.CONTROL.mainClass))
        assertTrue(wait.contains("-Dfluxzero.dev.control.agentReady=true"))
    }

    @Test
    fun `accepted port fallback starts server on a dynamic port`() {
        Files.writeString(projectDirectory.resolve("pom.xml"), "<project/>")
        val dependency = Files.createFile(projectDirectory.resolve("dev-server.jar"))
        var detachedCommand = emptyList<String>()
        val executor = object : CommandExecutor {
            override fun execute(command: List<String>, workingDirectory: Path, outputMode: OutputMode): Int {
                command.firstOrNull { it.startsWith("-Dmdep.outputFile=") }?.let {
                    Files.writeString(Path.of(it.substringAfter('=')), dependency.toString())
                    return 0
                }
                return if (command.contains("io.fluxzero.devserver.DevServerPreflightMain")) 75 else 0
            }

            override fun startDetached(command: List<String>, workingDirectory: Path, outputFile: Path): Long {
                detachedCommand = command
                return 4242
            }
        }

        val exitCode = DevServerLauncher(executor, emptyMap()) { }.launch(
            DevLaunchRequest(
                projectDirectory, "0-SNAPSHOT", DevLaunchTarget.SERVER,
                listOf("--project-dir", projectDirectory.toString(), "--port", "4200"), detached = true
            )
        )

        assertEquals(0, exitCode)
        assertEquals(listOf("--port", "0"), detachedCommand.takeLast(2))
    }

    @Test
    fun `rejected port fallback does not start server`() {
        Files.writeString(projectDirectory.resolve("pom.xml"), "<project/>")
        val dependency = Files.createFile(projectDirectory.resolve("dev-server.jar"))
        val executor = object : CommandExecutor {
            override fun execute(command: List<String>, workingDirectory: Path, outputMode: OutputMode): Int {
                command.firstOrNull { it.startsWith("-Dmdep.outputFile=") }?.let {
                    Files.writeString(Path.of(it.substringAfter('=')), dependency.toString())
                    return 0
                }
                return if (command.contains("io.fluxzero.devserver.DevServerPreflightMain")) 2 else 0
            }

            override fun startDetached(command: List<String>, workingDirectory: Path, outputFile: Path): Long {
                error("rejected preflight must not start the server")
            }
        }

        val exitCode = DevServerLauncher(executor, emptyMap()) { }.launch(
            DevLaunchRequest(projectDirectory, "0-SNAPSHOT", DevLaunchTarget.SERVER)
        )

        assertEquals(2, exitCode)
    }

    @Test
    fun `cancelled port selection exits successfully without starting server`() {
        Files.writeString(projectDirectory.resolve("pom.xml"), "<project/>")
        val dependency = Files.createFile(projectDirectory.resolve("dev-server.jar"))
        val executor = object : CommandExecutor {
            override fun execute(command: List<String>, workingDirectory: Path, outputMode: OutputMode): Int {
                command.firstOrNull { it.startsWith("-Dmdep.outputFile=") }?.let {
                    Files.writeString(Path.of(it.substringAfter('=')), dependency.toString())
                    return 0
                }
                return if (command.contains("io.fluxzero.devserver.DevServerPreflightMain")) 76 else 0
            }

            override fun startDetached(command: List<String>, workingDirectory: Path, outputFile: Path): Long {
                error("cancelled preflight must not start the server")
            }
        }

        val exitCode = DevServerLauncher(executor, emptyMap()) { }.launch(
            DevLaunchRequest(projectDirectory, "0-SNAPSHOT", DevLaunchTarget.SERVER)
        )

        assertEquals(0, exitCode)
    }

    @Test
    fun `detached launch materializes safe Fluxzero environment options without forwarding secrets`() {
        Files.writeString(projectDirectory.resolve("pom.xml"), "<project/>")
        val dependency = Files.createFile(projectDirectory.resolve("dev-server.jar"))
        var detachedCommand = emptyList<String>()
        val executor = object : CommandExecutor {
            override fun execute(command: List<String>, workingDirectory: Path, outputMode: OutputMode): Int {
                command.firstOrNull { it.startsWith("-Dmdep.outputFile=") }?.let {
                    Files.writeString(Path.of(it.substringAfter('=')), dependency.toString())
                }
                return 0
            }

            override fun startDetached(command: List<String>, workingDirectory: Path, outputFile: Path): Long {
                detachedCommand = command
                return 4242
            }
        }
        val environment = mapOf(
            "FLUXZERO_MAIN_CLASS" to "example.Main",
            "FLUXZERO_APPLICATION_NAME" to "Example",
            "FLUXZERO_NAMESPACE" to "local",
            "FLUXZERO_DEV_PORT" to "4200",
            "FLUXZERO_DEV_APPS" to "api, worker",
            "OP_SERVICE_ACCOUNT_TOKEN" to "never-forward-this"
        )

        DevServerLauncher(executor, environment) { }.launch(
            DevLaunchRequest(projectDirectory, "0-SNAPSHOT", detached = true)
        )

        assertTrue(detachedCommand.containsAll(listOf(
            "--main-class", "example.Main", "--application-name", "Example",
            "--namespace", "local", "--port", "4200", "--app", "api", "--app", "worker"
        )))
        assertTrue(detachedCommand.none { it.contains("never-forward-this") })
    }

    @Test
    fun `control target invokes lifecycle main`() {
        Files.writeString(projectDirectory.resolve("pom.xml"), "<project/>")
        val dependency = Files.createFile(projectDirectory.resolve("dev-server.jar"))
        val commands = mutableListOf<List<String>>()
        val executor = CommandExecutor { command, _, _ ->
            commands += command
            command.firstOrNull { it.startsWith("-Dmdep.outputFile=") }?.let {
                Files.writeString(Path.of(it.substringAfter('=')), dependency.toString())
            }
            0
        }

        DevServerLauncher(executor, emptyMap()) { }.launch(
            DevLaunchRequest(
                projectDirectory, "0-SNAPSHOT", DevLaunchTarget.CONTROL,
                listOf("status", "--project-dir", projectDirectory.toString())
            )
        )

        assertTrue(commands.last().contains(DevLaunchTarget.CONTROL.mainClass))
        assertTrue(commands.last().contains("status"))
    }

    @Test
    fun `lost attached terminal stops environment instead of detaching implicitly`() {
        Files.writeString(projectDirectory.resolve("pom.xml"), "<project/>")
        val launcherDirectory = Files.createDirectories(projectDirectory.resolve(".fluxzero/dev/launcher"))
        val dependency = Files.createFile(projectDirectory.resolve("dev-server.jar"))
        Files.writeString(launcherDirectory.resolve("classpath.txt"), dependency.toString())
        Files.writeString(launcherDirectory.resolve("version"), "1.2.3")
        Files.writeString(projectDirectory.resolve(".fluxzero/dev/session.json"),
                          """{"status":"running","pid":${ProcessHandle.current().pid()}}""")
        val commands = mutableListOf<List<String>>()
        var released = false
        val executor = object : CommandExecutor {
            override fun execute(command: List<String>, workingDirectory: Path, outputMode: OutputMode): Int {
                commands += command
                return when {
                    command.contains("probe") -> 0
                    command.contains("attach") -> 77
                    command.contains("stop") -> 0
                    else -> error("unexpected command: $command")
                }
            }

            override fun releaseDetached(workingDirectory: Path) {
                released = true
            }
        }

        val exitCode = DevServerLauncher(executor, emptyMap()) { }.launch(
            DevLaunchRequest(projectDirectory, "1.2.3", DevLaunchTarget.SERVER)
        )

        assertEquals(0, exitCode)
        assertTrue(commands.any { it.contains("stop") && it.contains("--force") })
        assertTrue(released)
    }

    @Test
    fun `global stop releases all detached operating system registrations`() {
        val launcherDirectory = Files.createDirectories(projectDirectory.resolve(".fluxzero/dev/launcher"))
        val dependency = Files.createFile(projectDirectory.resolve("dev-server.jar"))
        Files.writeString(launcherDirectory.resolve("classpath.txt"), dependency.toString())
        Files.writeString(launcherDirectory.resolve("version"), "1.2.3")
        var releasedAll = false
        val executor = object : CommandExecutor {
            override fun execute(command: List<String>, workingDirectory: Path, outputMode: OutputMode) = 0
            override fun releaseAllDetached() {
                releasedAll = true
            }
        }

        val exitCode = DevServerLauncher(executor, emptyMap()) { }.launch(
            DevLaunchRequest(projectDirectory, "1.2.3", DevLaunchTarget.CONTROL, listOf("stop", "--all"))
        )

        assertEquals(0, exitCode)
        assertTrue(releasedAll)
    }

    @Test
    fun `uses java from path when native runtime has no java home`() {
        Files.writeString(projectDirectory.resolve("pom.xml"), "<project/>")
        val dependency = Files.createFile(projectDirectory.resolve("dev-server.jar"))
        val commands = mutableListOf<List<String>>()
        val executor = CommandExecutor { command, _, _ ->
            commands += command
            command.firstOrNull { it.startsWith("-Dmdep.outputFile=") }?.let {
                Files.writeString(Path.of(it.substringAfter('=')), dependency.toString())
            }
            0
        }
        val javaHome = System.getProperty("java.home")
        System.clearProperty("java.home")
        try {
            DevServerLauncher(executor, emptyMap()) { }.launch(
                DevLaunchRequest(
                    projectDirectory, "0-SNAPSHOT", DevLaunchTarget.CONTROL,
                    listOf("status", "--project-dir", projectDirectory.toString())
                )
            )
        } finally {
            if (javaHome != null) System.setProperty("java.home", javaHome)
        }

        assertEquals("java", commands.last().first())
        assertTrue(commands.last().contains(DevLaunchTarget.CONTROL.mainClass))
    }

    @Test
    fun `resolves standalone artifact with project Gradle wrapper`() {
        Files.writeString(projectDirectory.resolve("build.gradle.kts"), "plugins { java }")
        Files.writeString(projectDirectory.resolve("gradlew"), "wrapper")
        val dependency = Files.createFile(projectDirectory.resolve("dev-server.jar"))
        val commands = mutableListOf<List<String>>()
        val executor = CommandExecutor { command, _, _ ->
            commands += command
            command.firstOrNull { it.startsWith("-PfluxzeroDevClasspath=") }?.let {
                Files.writeString(Path.of(it.substringAfter('=')), dependency.toString())
            }
            0
        }

        DevServerLauncher(executor, emptyMap()) { }.launch(
            DevLaunchRequest(projectDirectory, "0-SNAPSHOT", DevLaunchTarget.CONTROL, listOf("status"))
        )

        assertEquals(projectDirectory.resolve("gradlew").toAbsolutePath().toString(), commands.first().first())
        assertTrue(commands.first().contains("resolveFluxzeroDevServer"))
        assertTrue(commands.last().contains(DevLaunchTarget.CONTROL.mainClass))
        val launcherBuild = Files.readString(projectDirectory.resolve(".fluxzero/dev/launcher/build.gradle"))
        assertTrue(launcherBuild.contains("io.fluxzero.tools:fluxzero-dev-server:0-SNAPSHOT:standalone"))
    }

    private data class Invocation(
        val command: List<String>,
        val workingDirectory: Path,
        val outputMode: OutputMode
    )

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun assertCleanStop(messages: List<String>) {
        val stopping = messages.indexOfFirst { it.contains("Stopping Fluxzero dev server") }
        val stopped = messages.indexOfFirst { it.contains("Fluxzero dev server stopped.") }
        assertEquals(1, messages.count { it.contains("Stopping Fluxzero dev server") })
        assertEquals(1, messages.count { it.contains("Fluxzero dev server stopped.") })
        assertTrue(stopping >= 0 && stopped > stopping, messages.toString())
    }
}
