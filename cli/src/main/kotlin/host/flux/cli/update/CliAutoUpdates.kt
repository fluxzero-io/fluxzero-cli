package host.flux.cli.update

import host.flux.cli.services.VersionService
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Clock
import java.time.Duration
import java.util.Properties
import java.util.UUID

internal const val UPDATE_WORKER = "--internal-auto-update"
internal val UPDATE_INTERVAL: Duration = Duration.ofDays(3)

/** Settings are user-scoped; project files cannot silently opt a machine into executable updates. */
class CliUpdateSettings(val directory: Path = Path.of(System.getProperty("user.home"), ".fluxzero", "updates")) {
    private val file get() = directory.resolve("settings.properties")

    fun enabled(): Boolean? = readProperties(file).getProperty("enabled")?.toBooleanStrictOrNull()

    fun configure(action: String): Boolean? {
        require(action in setOf("status", "enable", "disable", "enable-if-unset")) { "Unknown automatic update preference" }
        if (action == "status") return enabled()
        Files.createDirectories(directory)
        FileChannel.open(directory.resolve("settings.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            channel.lock().use {
                val settings = readProperties(file)
                if (action != "enable-if-unset" || !settings.containsKey("enabled")) {
                    settings.setProperty("enabled", (action != "disable").toString())
                    writeProperties(file, settings)
                }
            }
        }
        return enabled()
    }

    internal fun <T> withInstallationLock(action: () -> T): T {
        Files.createDirectories(directory)
        FileChannel.open(directory.resolve("update.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            val lock = try { channel.tryLock() } catch (_: java.nio.channels.OverlappingFileLockException) { null }
            check(lock != null) { "Another CLI update is in progress. Try fz upgrade again after it finishes; see ${directory.resolve("last-run.log")}." }
            lock.use { return action() }
        }
    }
}

internal class CliAutoUpdates(
    private val settings: CliUpdateSettings = CliUpdateSettings(),
    private val clock: Clock = Clock.systemUTC(),
    private val environment: Map<String, String> = System.getenv(),
    private val launch: (String) -> Unit = { token -> launchUpdateWorker(token, settings.directory) },
    private val update: () -> String = {
        val source = readProperties(settings.directory.resolve("state.properties")).getProperty("sourceInstallation")
        val installation = source?.let { CliInstallation(Path.of(it), false) } ?: CliInstallation.current()
        CliUpdateInstaller(installation).update(VersionService.getCurrentVersion())
    },
    private val checkVersion: () -> String? = { CliReleaseClient().newestVersion() },
) {
    private val stateFile get() = settings.directory.resolve("state.properties")

    private fun checksPermitted(): Boolean = environment["CI"]?.lowercase() !in setOf("true", "1") &&
        environment["FLUXZERO_CLI_UPDATE_CHECK"]?.lowercase() !in setOf("false", "0", "off")

    private fun permitted(): Boolean = settings.enabled() == true &&
        environment["FLUXZERO_CLI_AUTO_UPDATE"]?.lowercase() !in setOf("false", "0", "off") &&
        environment["CI"]?.lowercase() !in setOf("true", "1")

    /** Only local bookkeeping and process creation happen on the command path. Failures never block CLI use. */
    fun schedule(currentVersion: String) {
        runCatching {
            if (CliVersion.parse(currentVersion) == null || !checksPermitted()) return
            withLock(wait = false) {
                val state = readProperties(stateFile)
                val now = clock.millis()
                val last = state.getProperty("lastAttempt")?.toLongOrNull()
                if (last != null && now - last in 0 until UPDATE_INTERVAL.toMillis()) return@withLock
                val token = UUID.randomUUID().toString()
                state.setProperty("lastAttempt", now.toString())
                state.setProperty("token", token)
                state.remove("sourceInstallation")
                state.setProperty("result", "scheduled")
                writeProperties(stateFile, state)
                // Record before spawning, including failed launches, to avoid retries on every invocation.
                runCatching { launch(token) }.onFailure {
                    state.setProperty("result", "Update deferred: ${it.message ?: it.javaClass.simpleName}")
                    state.remove("token")
                    writeProperties(stateFile, state)
                }
            }
        }
    }

    /** Read only cached release information on the foreground path, including when installation is disabled. */
    fun notification(current: String): String? {
        var message: String? = null
        runCatching {
            if (!checksPermitted()) return null
            val running = CliVersion.parse(current) ?: return null
            withLock(wait = false) {
                val state = readProperties(stateFile)
                val latest = state.getProperty("latestVersion") ?: return@withLock
                if ((CliVersion.parse(latest) ?: return@withLock) <= running) return@withLock
                val key = "$current->$latest"
                val previous = state.getProperty("lastNotice")?.toLongOrNull()
                if (state.getProperty("noticeVersion") == key && previous != null &&
                    clock.millis() - previous in 0 until UPDATE_INTERVAL.toMillis()) return@withLock
                message = "A new version of the Fluxzero CLI is available: $latest (current: $current). Run fz upgrade to update."
                state.setProperty("noticeVersion", key)
                state.setProperty("lastNotice", clock.millis().toString())
                writeProperties(stateFile, state)
            }
        }
        return message
    }

    fun runWorker(token: String) {
        runCatching {
            withLock(wait = true) {
                val state = readProperties(stateFile)
                if (state.getProperty("token") != token || state.getProperty("result") != "scheduled") return@withLock
                if (!checksPermitted()) return@withLock
                // Windows workers run from a private copy so their own mapped executable never needs replacement.
                if (isWindows() && System.getProperty("org.graalvm.nativeimage.imagecode") == "runtime" &&
                    permitted() && state.getProperty("sourceInstallation") == null) {
                    val original = CliInstallation.current()
                    if (!original.jar && host.flux.cli.services.ManagedPackageInstallation.detect(original.path) == null) {
                        val copy = copyWindowsWorker(original, settings.directory)
                        state.setProperty("sourceInstallation", original.path.toString())
                        writeProperties(stateFile, state)
                        startUpdateWorker(copy, token, settings.directory)
                        return@withLock
                    }
                }
                val result = runCatching {
                    checkVersion()?.let { latest ->
                        if (CliVersion.parse(latest) != null) {
                            state.setProperty("latestVersion", latest)
                            writeProperties(stateFile, state)
                        }
                    }
                    if (permitted()) update() else "Automatic installation disabled; update availability checked"
                }.getOrElse { "Update deferred: ${it.message ?: it.javaClass.simpleName}" }
                state.setProperty("result", result.take(1000))
                state.remove("token")
                writeProperties(stateFile, state)
                System.err.println(result)
            }
        }
    }

    private fun withLock(wait: Boolean, action: () -> Unit) {
        Files.createDirectories(settings.directory)
        FileChannel.open(settings.directory.resolve("update.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            val lock = runCatching { if (wait) channel.lock() else channel.tryLock() }.getOrNull() ?: return
            lock.use { action() }
        }
    }
}

internal fun readProperties(path: Path): Properties = Properties().apply {
    if (Files.exists(path)) Files.newInputStream(path).use(::load)
}

internal fun writeProperties(path: Path, properties: Properties) {
    Files.createDirectories(path.parent)
    val temporary = Files.createTempFile(path.parent, ".update-", ".tmp")
    try {
        Files.newOutputStream(temporary).use { properties.store(it, "Fluxzero CLI updates") }
        Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } finally {
        Files.deleteIfExists(temporary)
    }
}

internal data class CliVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<CliVersion> {
    override fun compareTo(other: CliVersion): Int = compareValuesBy(this, other, CliVersion::major, CliVersion::minor, CliVersion::patch)

    companion object {
        fun parse(value: String): CliVersion? {
            val match = Regex("v?(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)").matchEntire(value) ?: return null
            val parts = match.groupValues.drop(1).map { it.toIntOrNull() ?: return null }
            return CliVersion(parts[0], parts[1], parts[2])
        }
    }
}

internal data class CliInstallation(val path: Path, val jar: Boolean) {
    fun command(): List<String> = if (jar) {
        listOf(Path.of(System.getProperty("java.home"), "bin", if (isWindows()) "java.exe" else "java").toString(),
            "-Duser.home=${System.getProperty("user.home")}", "-jar", path.toString())
    } else listOf(path.toString(), "-Duser.home=${System.getProperty("user.home")}")

    companion object {
        fun current(): CliInstallation {
            if (System.getProperty("org.graalvm.nativeimage.imagecode") == "runtime") {
                return CliInstallation(Path.of(ProcessHandle.current().info().command().orElseThrow()).toRealPath(), false)
            }
            val path = Path.of(CliAutoUpdates::class.java.protectionDomain.codeSource.location.toURI()).toRealPath()
            check(path.fileName.toString().endsWith(".jar")) { "Automatic updates require an installed CLI" }
            return CliInstallation(path, true)
        }
    }
}

internal fun isWindows(): Boolean = System.getProperty("os.name").lowercase().contains("windows")

internal fun launchUpdateWorker(token: String, directory: Path) {
    startUpdateWorker(CliInstallation.current(), token, directory)
}

internal fun copyWindowsWorker(original: CliInstallation, directory: Path): CliInstallation {
    // Only called under update.lock; previous worker copies are no longer in use.
    Files.list(directory).use { entries ->
        entries.filter { Files.isDirectory(it) && it.fileName.toString().startsWith("worker-") }.forEach { old ->
            Files.walk(old).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { runCatching { Files.deleteIfExists(it) } } }
        }
    }
    val worker = Files.createTempDirectory(directory, "worker-")
    Files.copy(original.path, worker.resolve("fz.exe"))
    Files.list(original.path.parent).use { paths ->
        paths.filter { it.fileName.toString().endsWith(".dll", true) }.forEach { Files.copy(it, worker.resolve(it.fileName)) }
    }
    return CliInstallation(worker.resolve("fz.exe"), false)
}

private fun startUpdateWorker(installation: CliInstallation, token: String, directory: Path) {
    val command = installation.command() + listOf(UPDATE_WORKER, token)
    val detached = if (!isWindows() && Files.isExecutable(Path.of("/usr/bin/nohup"))) listOf("/usr/bin/nohup") + command else command
    ProcessBuilder(detached)
        .directory(directory.toFile())
        .redirectInput(ProcessBuilder.Redirect.from(Path.of(if (isWindows()) "NUL" else "/dev/null").toFile()))
        .redirectOutput(directory.resolve("last-run.log").toFile())
        .redirectErrorStream(true)
        .start()
}
