package host.flux.cli.update

import org.junit.jupiter.api.io.TempDir
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class CliAutoUpdatesTest {
    @TempDir lateinit var directory: Path
    private val now = Instant.parse("2026-10-13T12:00:00Z")

    private fun service(time: Instant = now, env: Map<String, String> = emptyMap(), launch: (String) -> Unit = {}, update: () -> String = { "updated" }) =
        CliAutoUpdates(CliUpdateSettings(directory), Clock.fixed(time, ZoneOffset.UTC), env, launch, update, { "1.2.4" })

    @Test fun `onboarding enables only an unset preference and retains unrelated settings`() {
        val settings = CliUpdateSettings(directory)
        assertNull(settings.configure("status"))
        assertFalse(Files.exists(directory.resolve("settings.properties")))
        Files.writeString(directory.resolve("settings.properties"), "other=keep\n")
        assertEquals(true, settings.configure("enable-if-unset"))
        assertEquals("keep", readProperties(directory.resolve("settings.properties")).getProperty("other"))
        assertEquals(false, settings.configure("disable"))
        assertEquals(false, settings.configure("enable-if-unset"))
        assertEquals(true, settings.configure("enable"))
    }

    @Test fun `disabled installation still checks and advertises updates without installing`() {
        for (preference in listOf("status", "disable")) {
            CliUpdateSettings(directory).configure(preference)
            var token = ""
            service(launch = { token = it }).schedule("1.2.3")
            if (token.isEmpty()) token = readProperties(directory.resolve("state.properties")).getProperty("token", "")
            if (token.isNotEmpty()) service(update = { fail("installation disabled") }).runWorker(token)
        }
        assertContains(service().notification("1.2.3")!!, "1.2.4")
        assertNull(service().notification("1.2.3"))
        assertNull(service().notification("1.2.4"))
        assertNotNull(service(now.plusSeconds(UPDATE_INTERVAL.seconds)).notification("1.2.3"))
    }

    @Test fun `CI check opt-out and development builds never launch`() {
        val calls = AtomicInteger()
        for (env in listOf(mapOf("CI" to "true"), mapOf("FLUXZERO_CLI_UPDATE_CHECK" to "false"))) {
            service(env = env, launch = { calls.incrementAndGet() }).schedule("1.2.3")
        }
        for (version in listOf("dev", "1.2.3-SNAPSHOT", "1.2.3-RC1", "unknown", "999999999999.0.0")) {
            service(launch = { calls.incrementAndGet() }).schedule(version)
        }
        assertEquals(0, calls.get())
    }

    @Test fun `environment install opt-out still permits notification lookup`() {
        CliUpdateSettings(directory).configure("enable")
        var token = ""
        val service = service(env = mapOf("FLUXZERO_CLI_AUTO_UPDATE" to "false"), launch = { token = it }, update = { fail("opt-out") })
        service.schedule("1.2.3")
        service.runWorker(token)
        assertNotNull(service.notification("1.2.3"))
    }

    @Test fun `concurrent starts launch once and next attempt waits three days`() {
        CliUpdateSettings(directory).configure("enable")
        val calls = AtomicInteger()
        val pool = Executors.newFixedThreadPool(8)
        try {
            val jobs = (1..20).map { pool.submit { service(launch = { calls.incrementAndGet() }).schedule("1.2.3") } }
            jobs.forEach { it.get(5, TimeUnit.SECONDS) }
        } finally { pool.shutdownNow() }
        service(now.plusSeconds(UPDATE_INTERVAL.seconds - 1), launch = { calls.incrementAndGet() }).schedule("1.2.3")
        assertEquals(1, calls.get())
        service(now.plusSeconds(UPDATE_INTERVAL.seconds), launch = { calls.incrementAndGet() }).schedule("1.2.3")
        assertEquals(2, calls.get())
    }

    @Test fun `failed launches and network failures do not retry every command`() {
        CliUpdateSettings(directory).configure("enable")
        service(launch = { error("cannot launch") }).schedule("1.2.3")
        service(launch = { fail("too soon") }).schedule("1.2.3")
        assertEquals("Update deferred: cannot launch", readProperties(directory.resolve("state.properties")).getProperty("result"))
        service(now.plusSeconds(UPDATE_INTERVAL.seconds)).schedule("1.2.3")
        val token = readProperties(directory.resolve("state.properties")).getProperty("token")
        service(update = { error("offline") }).runWorker(token)
        assertEquals("Update deferred: offline", readProperties(directory.resolve("state.properties")).getProperty("result"))
        service(launch = { fail("too soon") }).schedule("1.2.3")
    }

    @Test fun `manual upgrades share the worker lock`() {
        val settings = CliUpdateSettings(directory)
        settings.configure("enable")
        settings.withInstallationLock {
            service(launch = { fail("manual installation active") }).schedule("1.2.3")
        }
        assertFalse(Files.exists(directory.resolve("state.properties")))
    }

    @Test fun `worker requires its token rechecks opt-out and never replays`() {
        CliUpdateSettings(directory).configure("enable")
        var token = ""
        service(launch = { token = it }).schedule("1.2.3")
        service(update = { fail("wrong token") }).runWorker("other")
        CliUpdateSettings(directory).configure("disable")
        service(update = { fail("disabled") }).runWorker(token)
        CliUpdateSettings(directory).configure("enable")
        service(update = { fail("already consumed") }).runWorker(token)
        assertEquals("Automatic installation disabled; update availability checked", readProperties(directory.resolve("state.properties")).getProperty("result"))
    }

    @Test fun `a held worker lock cannot delay foreground scheduling`() {
        CliUpdateSettings(directory).configure("enable")
        FileChannel.open(directory.resolve("update.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            channel.lock().use { service(launch = { fail("worker already active") }).schedule("1.2.3") }
        }
        assertFalse(Files.exists(directory.resolve("state.properties")))
    }

    @Test fun `release selection excludes major jumps prereleases drafts downgrades and numeric traps`() {
        val releases = """[
          {"tag_name":"v2.0.0","draft":false,"prerelease":false},
          {"tag_name":"v1.3.0-rc.1","draft":false,"prerelease":false},
          {"tag_name":"v1.4.0","draft":false,"prerelease":true},
          {"tag_name":"v1.5.0","draft":true,"prerelease":false},
          {"tag_name":"v1.02.4","draft":false,"prerelease":false},
          {"tag_name":"v1.2.2","draft":false,"prerelease":false},
          {"tag_name":"v1.2.3","draft":false,"prerelease":false},
          {"tag_name":"v1.2.10","draft":false,"prerelease":false},
          {"tag_name":"v1.2.4","draft":false,"prerelease":false}
        ]"""
        assertEquals("v1.2.10", selectRelease(releases, "1.2.3")?.tag)
        assertNull(selectRelease(releases, "1.2.10"))
        assertNull(selectRelease(releases, "dev"))
    }
}
