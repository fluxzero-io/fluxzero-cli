package host.flux.dev

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
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
        val executor = CommandExecutor { command, workingDirectory, outputMode ->
            commands += Invocation(command, workingDirectory, outputMode)
            command.firstOrNull { it.startsWith("-Dmdep.outputFile=") }?.let {
                Files.writeString(Path.of(it.substringAfter('=')), dependency.toString())
            }
            0
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
        assertEquals(2, commands.size)
        assertEquals(OutputMode.STDOUT_TO_STDERR, commands[0].outputMode)
        assertEquals(projectDirectory.resolve("mvnw").toAbsolutePath().toString(), commands[0].command.first())
        val launcherPom = Files.readString(projectDirectory.resolve(".fluxzero/dev/launcher/pom.xml"))
        assertTrue(launcherPom.contains("<classifier>standalone</classifier>"))
        assertTrue(launcherPom.contains("<groupId>*</groupId>"))
        assertEquals(OutputMode.INHERIT, commands[1].outputMode)
        assertTrue(commands[1].command.contains("--enable-native-access=ALL-UNNAMED"))
        assertTrue(commands[1].command.contains("-Dfluxzero.dev.launcherOwnsShutdown=true"))
        assertTrue(commands[1].command.contains(DevLaunchTarget.SERVER.mainClass))
        assertTrue(commands[1].command.contains("--fast-compiler"))
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
    fun `refreshes snapshot classpath instead of reusing the cache`() {
        Files.writeString(projectDirectory.resolve("pom.xml"), "<project/>")
        val launcherDirectory = Files.createDirectories(projectDirectory.resolve(".fluxzero/dev/launcher"))
        val dependency = Files.createFile(projectDirectory.resolve("dev-server.jar"))
        Files.writeString(launcherDirectory.resolve("classpath.txt"), dependency.toString())
        Files.writeString(launcherDirectory.resolve("version"), "0-SNAPSHOT")
        val commands = mutableListOf<List<String>>()
        val executor = CommandExecutor { command, _, _ ->
            commands += command
            command.firstOrNull { it.startsWith("-Dmdep.outputFile=") }?.let {
                Files.writeString(Path.of(it.substringAfter('=')), dependency.toString())
            }
            0
        }

        DevServerLauncher(executor, emptyMap()) { }.launch(
            DevLaunchRequest(projectDirectory, "0-SNAPSHOT", DevLaunchTarget.SERVER)
        )

        assertEquals(2, commands.size)
        assertTrue(commands.first().any { it.contains("maven-dependency-plugin") })
        assertTrue(commands.last().contains(DevLaunchTarget.SERVER.mainClass))
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
    fun `reports one clean stop when snapshot resolution is interrupted`() {
        Files.writeString(projectDirectory.resolve("pom.xml"), "<project/>")
        val messages = mutableListOf<String>()
        val launcher = DevServerLauncher(CommandExecutor { _, _, _ -> 130 }, emptyMap(), messages::add)

        val exitCode = launcher.launch(
            DevLaunchRequest(projectDirectory, "0-SNAPSHOT", DevLaunchTarget.SERVER)
        )

        assertEquals(130, exitCode)
        assertEquals(1, messages.count { it.contains("Fluxzero dev stopped.") })
        assertTrue(messages.last().endsWith("Fluxzero dev stopped."))
    }

    @Test
    fun `shutdown callback and signal exit converge on one outcome`() {
        Files.writeString(projectDirectory.resolve("pom.xml"), "<project/>")
        val dependency = Files.createFile(projectDirectory.resolve("dev-server.jar"))
        val messages = mutableListOf<String>()
        var invocation = 0
        lateinit var shutdown: () -> Unit
        val executor = object : CommandExecutor {
            override fun execute(command: List<String>, workingDirectory: Path, outputMode: OutputMode): Int {
                invocation++
                if (invocation == 1) {
                    command.first { it.startsWith("-Dmdep.outputFile=") }.let {
                        Files.writeString(Path.of(it.substringAfter('=')), dependency.toString())
                    }
                    return 0
                }
                shutdown()
                return 130
            }

            override fun <T> supervise(onShutdown: () -> Unit, action: () -> T): T {
                shutdown = onShutdown
                return action()
            }
        }

        val exitCode = DevServerLauncher(executor, emptyMap(), messages::add).launch(
            DevLaunchRequest(projectDirectory, "0-SNAPSHOT", DevLaunchTarget.SERVER)
        )

        assertEquals(130, exitCode)
        assertEquals(1, messages.count { it.contains("Fluxzero dev stopped.") })
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
                } else {
                    events += "server"
                }
                return 0
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

        assertEquals(listOf("supervise-start", "resolve", "server", "supervise-end"), events)
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
        assertTrue(launcherBuild.contains("io.fluxzero:dev-server:0-SNAPSHOT:standalone"))
    }

    private data class Invocation(
        val command: List<String>,
        val workingDirectory: Path,
        val outputMode: OutputMode
    )
}
