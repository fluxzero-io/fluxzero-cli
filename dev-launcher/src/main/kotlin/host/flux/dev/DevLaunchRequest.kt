package host.flux.dev

import java.nio.file.Path

enum class DevLaunchTarget(val mainClass: String) {
    SERVER("io.fluxzero.devserver.DevServerMain"),
    MCP_STDIO("io.fluxzero.devserver.DevMcpStdioMain"),
    CONTROL("io.fluxzero.devserver.DevServerControlMain"),
    CONFIG("io.fluxzero.devserver.DevProjectConfigMain")
}

data class DevLaunchRequest(
    val projectDirectory: Path,
    val devServerVersion: String? = null,
    val target: DevLaunchTarget = DevLaunchTarget.SERVER,
    val arguments: List<String> = emptyList(),
    val detached: Boolean = false,
    val jvmOptions: List<String> = emptyList()
)
