package host.flux.desktop.services

import host.flux.desktop.model.AgentChoice
import host.flux.desktop.model.DesktopBuildSystem
import java.awt.Desktop
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.TimeUnit
import javax.swing.JOptionPane
import javax.swing.SwingUtilities

sealed interface FluxzeroDeepLink {
    val id: Long
}

data class FluxzeroNewProjectLink(
    override val id: Long,
    val name: String?,
    val prompt: String?,
    val template: String?,
    val location: String?,
    val agentChoice: AgentChoice?
) : FluxzeroDeepLink

sealed interface FluxzeroDirectLink : FluxzeroDeepLink {
    val agentChoice: AgentChoice
    val prompt: String?
}

data class FluxzeroOpenAgentLink(
    override val id: Long,
    val path: String,
    override val prompt: String?,
    override val agentChoice: AgentChoice
) : FluxzeroDirectLink

data class FluxzeroCreateProjectLink(
    override val id: Long,
    val name: String?,
    val template: String?,
    val location: String?,
    val groupId: String?,
    val artifactId: String?,
    val packageName: String?,
    val description: String?,
    val buildSystem: DesktopBuildSystem?,
    val initGit: Boolean,
    override val prompt: String?,
    override val agentChoice: AgentChoice
) : FluxzeroDirectLink

enum class StartupDeepLinkResult {
    NONE,
    UI,
    HEADLESS
}

object DeepLinkRouter {
    private val nextId = AtomicLong(0)
    private val headlessLinkHandled = AtomicBoolean(false)
    private val listeners = mutableSetOf<(FluxzeroNewProjectLink) -> Unit>()
    private val pendingLinks = mutableListOf<FluxzeroNewProjectLink>()
    private val headlessTasks = mutableListOf<CompletableFuture<*>>()

    fun registerSystemHandler() {
        registerWindowsProtocolHandler()
        runCatching {
            if (!Desktop.isDesktopSupported()) {
                return
            }
            val desktop = Desktop.getDesktop()
            if (!desktop.isSupported(Desktop.Action.APP_OPEN_URI)) {
                return
            }
            desktop.setOpenURIHandler { event -> submit(event.uri) }
        }
    }

    fun submit(rawUrl: String) {
        runCatching { submit(URI(rawUrl)) }
    }

    fun submitStartup(rawUrl: String): StartupDeepLinkResult {
        val link = runCatching { parse(URI(rawUrl)) }.getOrNull() ?: return StartupDeepLinkResult.NONE
        return when (link) {
            is FluxzeroNewProjectLink -> {
                queueUiLink(link)
                StartupDeepLinkResult.UI
            }
            is FluxzeroDirectLink -> {
                runHeadless(link, async = false)
                StartupDeepLinkResult.HEADLESS
            }
        }
    }

    fun submit(uri: URI) {
        val link = parse(uri) ?: return
        when (link) {
            is FluxzeroNewProjectLink -> queueUiLink(link)
            is FluxzeroDirectLink -> runHeadless(link, async = true)
        }
    }

    fun hasPendingUiLinks(): Boolean {
        return synchronized(listeners) { pendingLinks.isNotEmpty() }
    }

    fun hasHeadlessActionStarted(): Boolean {
        return headlessLinkHandled.get()
    }

    fun waitForHeadlessTasks(timeout: Long = 10, unit: TimeUnit = TimeUnit.MINUTES) {
        val tasks = synchronized(headlessTasks) { headlessTasks.toList() }
        if (tasks.isEmpty()) {
            return
        }
        CompletableFuture.allOf(*tasks.toTypedArray()).get(timeout, unit)
    }

    private fun queueUiLink(link: FluxzeroNewProjectLink) {
        val snapshot = synchronized(listeners) {
            if (listeners.isEmpty()) {
                pendingLinks += link
                return
            }
            listeners.toList()
        }
        SwingUtilities.invokeLater {
            snapshot.forEach { it(link) }
        }
    }

    private fun runHeadless(link: FluxzeroDirectLink, async: Boolean) {
        headlessLinkHandled.set(true)
        val action = {
            runCatching {
                DeepLinkActionRunner().run(link)
            }.onFailure {
                showHeadlessError(it)
            }
            Unit
        }
        if (!async) {
            action()
            return
        }
        val task = CompletableFuture.runAsync(action)
        synchronized(headlessTasks) {
            headlessTasks.add(task)
        }
        task.whenComplete { _, _ ->
            synchronized(headlessTasks) {
                headlessTasks.remove(task)
            }
        }
    }

    fun addListener(listener: (FluxzeroNewProjectLink) -> Unit): () -> Unit {
        val pending = synchronized(listeners) {
            listeners += listener
            pendingLinks.toList().also { pendingLinks.clear() }
        }
        if (pending.isNotEmpty()) {
            SwingUtilities.invokeLater {
                pending.forEach(listener)
            }
        }
        return {
            synchronized(listeners) {
                listeners -= listener
            }
        }
    }

    fun parse(uri: URI): FluxzeroDeepLink? {
        if (!uri.scheme.equals("fluxzero", ignoreCase = true)) {
            return null
        }
        val command = uri.host
            ?: uri.path.trim('/').substringBefore('/').takeIf { it.isNotBlank() }
            ?: return null
        val params = queryParams(uri)
        return when (command.lowercase()) {
            "new" -> FluxzeroNewProjectLink(
                id = nextId.incrementAndGet(),
                name = params["name"],
                prompt = params["prompt"],
                template = params["template"],
                location = params["location"] ?: params["path"],
                agentChoice = parseAgent(params["agent"])
            )
            "open", "codex", "claude", "claude-code" -> parseOpenLink(command, params)
            "create" -> parseCreateLink(params)
            else -> null
        }
    }

    private fun parseOpenLink(command: String, params: Map<String, String>): FluxzeroOpenAgentLink? {
        val path = params["path"] ?: params["location"] ?: return null
        val agent = when (command.lowercase()) {
            "codex" -> AgentChoice.CODEX
            "claude", "claude-code" -> AgentChoice.CLAUDE
            else -> parseAgent(params["agent"]) ?: AgentChoice.CODEX
        }
        return FluxzeroOpenAgentLink(
            id = nextId.incrementAndGet(),
            path = path,
            prompt = params["prompt"],
            agentChoice = agent
        )
    }

    private fun parseCreateLink(params: Map<String, String>): FluxzeroCreateProjectLink {
        return FluxzeroCreateProjectLink(
            id = nextId.incrementAndGet(),
            name = params["name"],
            template = params["template"],
            location = params["location"] ?: params["path"],
            groupId = params["groupId"] ?: params["group"],
            artifactId = params["artifactId"] ?: params["artifact"],
            packageName = params["package"] ?: params["packageName"],
            description = params["description"],
            buildSystem = parseBuildSystem(params["build"] ?: params["buildSystem"]),
            initGit = parseBoolean(params["git"]) ?: true,
            prompt = params["prompt"],
            agentChoice = parseAgent(params["agent"]) ?: AgentChoice.CODEX
        )
    }

    private fun queryParams(uri: URI): Map<String, String> {
        return uri.rawQuery
            ?.split("&")
            ?.mapNotNull { pair ->
                val separator = pair.indexOf("=")
                if (separator < 0) {
                    return@mapNotNull null
                }
                val key = decode(pair.substring(0, separator)).takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val value = decode(pair.substring(separator + 1))
                key to value
            }
            ?.toMap()
            ?: emptyMap()
    }

    private fun parseAgent(value: String?): AgentChoice? {
        return when (value?.lowercase()?.replace("_", "-")) {
            "codex" -> AgentChoice.CODEX
            "claude", "claude-code" -> AgentChoice.CLAUDE
            "both", "all" -> AgentChoice.BOTH
            "none", "generate" -> AgentChoice.NONE
            else -> null
        }
    }

    private fun parseBuildSystem(value: String?): DesktopBuildSystem? {
        return when (value?.lowercase()) {
            "maven", "mvn" -> DesktopBuildSystem.MAVEN
            "gradle" -> DesktopBuildSystem.GRADLE
            else -> null
        }
    }

    private fun parseBoolean(value: String?): Boolean? {
        return when (value?.lowercase()) {
            "1", "true", "yes", "y" -> true
            "0", "false", "no", "n" -> false
            else -> null
        }
    }

    private fun decode(value: String): String {
        return URLDecoder.decode(value, StandardCharsets.UTF_8)
    }

    private fun registerWindowsProtocolHandler() {
        if (!System.getProperty("os.name").contains("windows", ignoreCase = true)) {
            return
        }
        val executable = ProcessHandle.current().info().command().orElse(null) ?: return
        val command = "\"$executable\" \"%1\""
        val entries = listOf(
            listOf("reg", "add", "HKCU\\Software\\Classes\\fluxzero", "/ve", "/d", "URL:Fluxzero Launchpad", "/f"),
            listOf("reg", "add", "HKCU\\Software\\Classes\\fluxzero", "/v", "URL Protocol", "/d", "", "/f"),
            listOf("reg", "add", "HKCU\\Software\\Classes\\fluxzero\\DefaultIcon", "/ve", "/d", "\"$executable\",0", "/f"),
            listOf("reg", "add", "HKCU\\Software\\Classes\\fluxzero\\shell\\open\\command", "/ve", "/d", command, "/f")
        )
        entries.forEach { entry ->
            runCatching {
                ProcessBuilder(entry)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
                    .waitFor(2, TimeUnit.SECONDS)
            }
        }
    }

    private fun showHeadlessError(error: Throwable) {
        runCatching {
            JOptionPane.showMessageDialog(
                null,
                error.message ?: "Could not handle the Fluxzero link.",
                "Fluxzero Launchpad",
                JOptionPane.ERROR_MESSAGE
            )
        }
    }
}
