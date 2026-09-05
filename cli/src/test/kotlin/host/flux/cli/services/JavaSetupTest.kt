package host.flux.cli.services

import host.flux.cli.prompt.Prompt
import host.flux.dev.JavaRequiredException
import host.flux.dev.JavaRuntimeDiscovery
import host.flux.dev.RuntimeCommandResult
import host.flux.dev.RuntimeCommandRunner
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JavaSetupTest {
    private val root = createTempDirectory("java-setup")

    @Test
    fun `installs java after an explicit interactive choice`() {
        val brewDirectory = root.resolve("bin").createDirectories()
        val brew = Files.createFile(brewDirectory.resolve("brew"))
        val javaHome = root.resolve("openjdk@25")
        var installed = false
        val messages = mutableListOf<String>()
        val prompt = SelectionPrompt(0)
        val discovery = JavaRuntimeDiscovery(
            environment = mapOf("PATH" to brewDirectory.toString()),
            javaHome = null,
            osName = "Linux",
            commandRunner = RuntimeCommandRunner { command ->
                when {
                    command.first() == brew.toString() && installed -> RuntimeCommandResult(0, javaHome.toString())
                    command.first() == brew.toString() -> RuntimeCommandResult(1, "")
                    else -> RuntimeCommandResult(0, "openjdk version \"25.0.2\"")
                }
            }
        )
        val setup = JavaSetup(
            discovery = discovery,
            installer = {
                JavaInstaller {
                    val bin = javaHome.resolve("bin").createDirectories()
                    Files.createFile(bin.resolve("java"))
                    Files.createFile(bin.resolve("javac"))
                    installed = true
                    true
                }
            },
            prompt = { prompt },
            messageSink = messages::add
        )

        val runtime = setup.resolve()

        assertTrue(installed)
        assertEquals(25, runtime.feature)
        assertEquals(
            listOf("Java 25 is needed to start Fluxzero.", "", "", "Installing Java 25..."),
            messages
        )
        assertEquals("Would you like to install it now?", prompt.question)
        assertEquals(listOf("Install Java 25", "Cancel"), prompt.options)
    }

    @Test
    fun `does not install when the user cancels`() {
        var installed = false
        val setup = JavaSetup(
            discovery = missingJava(),
            installer = { JavaInstaller { installed = true; true } },
            prompt = { SelectionPrompt(1) },
            messageSink = {}
        )

        assertFailsWith<JavaRequiredException> { setup.resolve() }
        assertFalse(installed)
    }

    @Test
    fun `does not prompt or install for an agent transport`() {
        var installerRequested = false
        var promptRequested = false
        val setup = JavaSetup(
            discovery = missingJava(),
            installer = { installerRequested = true; JavaInstaller { true } },
            prompt = { promptRequested = true; SelectionPrompt(0) },
            interactive = false
        )

        val error = assertFailsWith<JavaRequiredException> { setup.resolve() }

        assertEquals("Java 25 is needed to start Fluxzero. Install it and try again.", error.message)
        assertFalse(installerRequested)
        assertFalse(promptRequested)
    }

    @Test
    fun `uses Homebrew on macOS and Linux`() {
        val commands = mutableListOf<List<String>>()
        val installer = SystemJavaInstaller(
            osName = "Mac OS X",
            commandAvailable = { it.first() == "brew" },
            execute = { commands += it; true }
        ).available()

        assertTrue(installer?.install() == true)
        assertEquals(listOf("brew", "install", "openjdk@25"), commands.single())
    }

    @Test
    fun `uses WinGet on Windows`() {
        val commands = mutableListOf<List<String>>()
        val installer = SystemJavaInstaller(
            osName = "Windows 11",
            commandAvailable = { it.first() == "winget" },
            execute = { commands += it; true }
        ).available()

        assertTrue(installer?.install() == true)
        assertEquals(
            listOf(
                "winget", "install", "--exact", "--id", "EclipseAdoptium.Temurin.25.JDK",
                "--accept-package-agreements", "--accept-source-agreements", "--disable-interactivity"
            ),
            commands.single()
        )
    }

    private fun missingJava() = JavaRuntimeDiscovery(
        environment = emptyMap(),
        javaHome = null,
        osName = "Linux",
        commandRunner = RuntimeCommandRunner { null }
    )

    private class SelectionPrompt(private val selection: Int) : Prompt {
        var question: String? = null
        var options: List<String>? = null

        override fun readLine(prompt: String): String = ""

        override fun select(question: String, options: List<String>, defaultIndex: Int): Int {
            this.question = question
            this.options = options
            return selection
        }
    }
}
