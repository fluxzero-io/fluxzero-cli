package host.flux.cli.commands

import com.github.ajalt.clikt.testing.test
import host.flux.dev.DevLaunchRequest
import host.flux.dev.DevLaunchTarget
import host.flux.dev.DevLauncher
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
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
                "--environment", "local",
                "--port", "4200",
                "--idp", "external",
                "--fast-compiler",
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
        assertTrue(request!!.arguments.containsAll(listOf("--environment", "local")))
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
        var request: DevLaunchRequest? = null
        val launcher = DevLauncher { captured -> request = captured; 0 }

        val result = Mcp(launcher).test(
            listOf("--project-dir", projectDirectory.toString(), "--dev-server-version", "0-SNAPSHOT")
        )

        assertEquals(0, result.statusCode)
        assertEquals(DevLaunchTarget.MCP_STDIO, request?.target)
        assertEquals(listOf("--project-dir", projectDirectory.toAbsolutePath().normalize().toString()), request?.arguments)
    }

    @Test
    fun `mcp command can ensure one background environment before connecting`() {
        val requests = mutableListOf<DevLaunchRequest>()
        val launcher = DevLauncher { captured -> requests += captured; 0 }

        val result = Mcp(launcher).test(
            listOf("--project-dir", projectDirectory.toString(), "--dev-server-version", "1-SNAPSHOT", "--ensure-dev")
        )

        assertEquals(0, result.statusCode)
        assertEquals(2, requests.size)
        assertEquals(DevLaunchTarget.SERVER, requests[0].target)
        assertTrue(requests[0].detached)
        assertEquals(DevLaunchTarget.MCP_STDIO, requests[1].target)
        assertEquals("1-SNAPSHOT", requests[1].devServerVersion)
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
    fun `rejects start outside a Maven or Gradle project before launching`() {
        var launched = false
        val result = Dev { launched = true; 0 }.test(
            listOf("--project-dir", projectDirectory.toString())
        )

        assertEquals(1, result.statusCode)
        assertTrue(!launched)
        assertTrue(result.output.contains("No Maven or Gradle project found in '$projectDirectory'"))
        assertTrue(result.output.contains("--project-dir <path>"))
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
        val result = Dev { 0 }.test("--help")

        assertEquals(0, result.statusCode)
        assertTrue(result.output.contains("fz dev config"))
        assertTrue(result.output.contains(".fluxzero/dev.yaml"))
    }
}
