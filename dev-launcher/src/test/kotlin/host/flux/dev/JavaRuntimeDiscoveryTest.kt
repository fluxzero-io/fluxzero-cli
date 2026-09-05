package host.flux.dev

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class JavaRuntimeDiscoveryTest {
    private val root = createTempDirectory("java-runtime-discovery")

    @Test
    fun `uses compatible java home without querying fallback installations`() {
        val javaHome = javaHome("jdk-25")
        val commands = mutableListOf<List<String>>()
        val discovery = JavaRuntimeDiscovery(
            environment = mapOf("JAVA_HOME" to javaHome.toString()),
            javaHome = null,
            osName = "Linux",
            commandRunner = RuntimeCommandRunner { command ->
                commands += command
                RuntimeCommandResult(0, "openjdk version \"25.0.2\"")
            }
        )

        val runtime = discovery.resolve()

        assertEquals(javaHome.toRealPath(), runtime.home)
        assertEquals(25, runtime.feature)
        assertEquals(1, commands.size)
    }

    @Test
    fun `skips an older java and finds a compatible one on path`() {
        val oldHome = javaHome("jdk-21")
        val currentHome = javaHome("jdk-25")
        val discovery = JavaRuntimeDiscovery(
            environment = mapOf(
                "JAVA_HOME" to oldHome.toString(),
                "PATH" to currentHome.resolve("bin").toString()
            ),
            javaHome = null,
            osName = "Linux",
            commandRunner = versionRunner(oldHome to 21, currentHome to 25)
        )

        val runtime = discovery.resolve()

        assertEquals(currentHome.toRealPath(), runtime.home)
        assertEquals(25, runtime.feature)
    }

    @Test
    fun `finds keg only Homebrew installation`() {
        val brewDirectory = root.resolve("homebrew/bin").createDirectories()
        val brew = Files.createFile(brewDirectory.resolve("brew"))
        val javaHome = javaHome("homebrew/opt/openjdk@25")
        val discovery = JavaRuntimeDiscovery(
            environment = mapOf("PATH" to brewDirectory.toString()),
            javaHome = null,
            osName = "Linux",
            commandRunner = RuntimeCommandRunner { command ->
                when (command.first()) {
                    brew.toString() -> RuntimeCommandResult(0, javaHome.toString())
                    else -> RuntimeCommandResult(0, "openjdk version \"25.0.2\"")
                }
            }
        )

        assertEquals(javaHome.toRealPath(), discovery.resolve().home)
    }

    @Test
    fun `requires a development kit instead of a runtime only`() {
        val home = root.resolve("jre-25")
        val bin = home.resolve("bin").createDirectories()
        Files.createFile(bin.resolve("java"))
        val discovery = JavaRuntimeDiscovery(
            environment = mapOf("JAVA_HOME" to home.toString()),
            javaHome = null,
            osName = "Linux",
            commandRunner = RuntimeCommandRunner { RuntimeCommandResult(0, "openjdk version \"25.0.2\"") }
        )

        assertNull(discovery.find())
        assertEquals(
            "Java 25 is needed to start Fluxzero. Install it and try again.",
            assertFailsWith<JavaRequiredException> { discovery.resolve() }.message
        )
    }

    @Test
    fun `finds a newer supported Windows development kit`() {
        val programFiles = root.resolve("Program Files")
        val javaHome = programFiles.resolve("Eclipse Adoptium/jdk-26.0.1")
        val bin = javaHome.resolve("bin").createDirectories()
        Files.createFile(bin.resolve("java.exe"))
        Files.createFile(bin.resolve("javac.exe"))
        val discovery = JavaRuntimeDiscovery(
            environment = mapOf("ProgramFiles" to programFiles.toString()),
            javaHome = null,
            osName = "Windows 11",
            commandRunner = RuntimeCommandRunner { RuntimeCommandResult(0, "openjdk version \"26.0.1\"") }
        )

        assertEquals(javaHome.toRealPath(), discovery.resolve().home)
    }

    private fun javaHome(relative: String): Path {
        val home = root.resolve(relative)
        val bin = home.resolve("bin").createDirectories()
        Files.createFile(bin.resolve("java"))
        Files.createFile(bin.resolve("javac"))
        return home
    }

    private fun versionRunner(vararg versions: Pair<Path, Int>) = RuntimeCommandRunner { command ->
        val version = versions.first { (home, _) -> command.first().startsWith(home.toRealPath().toString()) }.second
        RuntimeCommandResult(0, "openjdk version \"$version.0.2\"")
    }
}
