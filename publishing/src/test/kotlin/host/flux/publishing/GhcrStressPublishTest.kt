package host.flux.publishing

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Collections
import kotlin.math.min
import kotlin.random.Random
import kotlin.test.fail
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("ghcr")
class GhcrStressPublishTest {
    @Test
    fun repeatedlyPublishesFreshImagesToGhcr() {
        assumeTrue(
            systemBoolean("fluxzero.ghcrStress", defaultValue = false),
            "Set -Dfluxzero.ghcrStress=true to run the GHCR stress test"
        )

        val username = configured("fluxzero.ghcrStress.username", "GHCR_USERNAME") ?: "jbruinink"
        val token = configured("fluxzero.ghcrStress.token", "GHCR_TOKEN")
            ?: configured("fluxzero.ghcrStress.token", "GITHUB_TOKEN")
        assumeTrue(!token.isNullOrBlank(), "Set GHCR_TOKEN or GITHUB_TOKEN with package write access")

        val iterations = systemInt("fluxzero.ghcrStress.iterations", defaultValue = 20)
        val dependencyCount = systemInt("fluxzero.ghcrStress.dependencyCount", defaultValue = 8)
        val dependencySizeKiB = systemInt("fluxzero.ghcrStress.dependencySizeKiB", defaultValue = 256)
        val uniqueImages = systemBoolean("fluxzero.ghcrStress.uniqueImages", defaultValue = false)
        val publishAttempts = systemInt("fluxzero.ghcrStress.publishAttempts", JavaPackagePublishSpec.DEFAULT_PUBLISH_ATTEMPTS)
        val publishRetryDelayMillis = systemLong("fluxzero.ghcrStress.publishRetryDelayMillis", JavaPackagePublishSpec.DEFAULT_PUBLISH_RETRY_DELAY_MILLIS)
        val repository = System.getProperty("fluxzero.ghcrStress.repository")
            ?: "ghcr.io/$username/fluxzero-cli-ghcr-stress"
        val runId = System.getProperty("fluxzero.ghcrStress.runId")
            ?: DateTimeFormatter.ofPattern("yyyyMMddHHmmss").format(java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC))
        val logDirectory = Path.of(
            System.getProperty(
                "fluxzero.ghcrStress.logDir",
                "build/ghcr-stress/$runId"
            )
        ).toAbsolutePath()
        Files.createDirectories(logDirectory)

        val summary = mutableListOf<String>()
        summary += "runId=$runId"
        summary += "started=${Instant.now()}"
        summary += "repository=$repository"
        summary += "iterations=$iterations"
        summary += "dependencyCount=$dependencyCount"
        summary += "dependencySizeKiB=$dependencySizeKiB"
        summary += "uniqueImages=$uniqueImages"
        summary += "publishAttempts=$publishAttempts"
        summary += "publishRetryDelayMillis=$publishRetryDelayMillis"
        summary += ""

        for (attempt in 1..iterations) {
            val attemptId = "$runId-$attempt"
            val attemptEvents = Collections.synchronizedList(mutableListOf<JavaPackagePublishDiagnosticEvent>())
            val image = if (uniqueImages) "$repository-$attemptId" else repository
            val tags = listOf("stress-$attemptId", "stress-$attemptId-extra")
            val attemptStarted = Instant.now()

            try {
                val spec = stressSpec(
                    image = image,
                    tags = tags,
                    username = username,
                    token = token!!,
                    attemptId = attemptId,
                    dependencyCount = dependencyCount,
                    dependencySizeKiB = dependencySizeKiB,
                    publishAttempts = publishAttempts,
                    publishRetryDelayMillis = publishRetryDelayMillis
                )
                val results = JavaPackagePublisher(JavaPackagePublishDiagnostics { event ->
                    attemptEvents += event
                }).publish(spec)
                val digests = results.joinToString { "${it.packageReference}@${it.digest}" }
                summary += "attempt=$attempt status=OK started=$attemptStarted finished=${Instant.now()} image=$image tags=${tags.joinToString(",")} results=$digests"
                writeAttemptLog(logDirectory, attempt, attemptEvents)
                println("GHCR stress attempt $attempt/$iterations OK: $digests")
            } catch (exception: Exception) {
                summary += "attempt=$attempt status=FAILED started=$attemptStarted finished=${Instant.now()} image=$image tags=${tags.joinToString(",")} error=${exception::class.qualifiedName}: ${exception.message}"
                writeAttemptLog(logDirectory, attempt, attemptEvents, exception)
                writeSummary(logDirectory, summary)
                fail("GHCR stress attempt $attempt failed. Diagnostics: ${logDirectory.resolve(attemptLogName(attempt))}", exception)
            }
        }

        summary += ""
        summary += "finished=${Instant.now()}"
        writeSummary(logDirectory, summary)
        println("GHCR stress test completed. Diagnostics: $logDirectory")
    }

    private fun stressSpec(
        image: String,
        tags: List<String>,
        username: String,
        token: String,
        attemptId: String,
        dependencyCount: Int,
        dependencySizeKiB: Int,
        publishAttempts: Int,
        publishRetryDelayMillis: Long
    ): JavaPackagePublishSpec {
        val classesDirectory = Files.createTempDirectory("fluxzero-ghcr-stress-classes-")
        Files.createDirectories(classesDirectory.resolve("com/example"))
        Files.writeString(
            classesDirectory.resolve("com/example/Application.class"),
            "synthetic class payload for $attemptId at ${Instant.now()}\n"
        )

        val dependencyDirectory = Files.createTempDirectory("fluxzero-ghcr-stress-deps-")
        val dependencies = (1..dependencyCount).map { index ->
            val dependency = dependencyDirectory.resolve("stress-$attemptId-dependency-$index.jar")
            writeDeterministicBytes(
                dependency,
                sizeBytes = dependencySizeKiB * 1024,
                seed = "$attemptId-$index".hashCode()
            )
            JavaPackageDependency(dependency)
        }

        return JavaPackagePublishSpec(
            registryHost = "ghcr.io",
            registryUsername = username,
            registryToken = token,
            packageName = "fluxzero-cli-ghcr-stress",
            packageVersion = tags.first(),
            mainClass = "com.example.Application",
            classesDirectory = classesDirectory,
            dependencies = dependencies,
            images = listOf(image),
            tags = tags,
            credentials = listOf(
                JavaPackageRegistryCredential(
                    registryHost = "ghcr.io",
                    registryUsername = username,
                    registryToken = token
                )
            ),
            toolName = "fluxzero-ghcr-stress-test",
            publishAttempts = publishAttempts,
            publishRetryDelayMillis = publishRetryDelayMillis
        )
    }

    private fun writeDeterministicBytes(path: Path, sizeBytes: Int, seed: Int) {
        val random = Random(seed)
        val buffer = ByteArray(64 * 1024)
        var remaining = sizeBytes
        Files.newOutputStream(path).use { output ->
            while (remaining > 0) {
                random.nextBytes(buffer)
                val count = min(buffer.size, remaining)
                output.write(buffer, 0, count)
                remaining -= count
            }
        }
    }

    private fun writeAttemptLog(
        logDirectory: Path,
        attempt: Int,
        events: List<JavaPackagePublishDiagnosticEvent>,
        exception: Exception? = null
    ) {
        val lines = mutableListOf<String>()
        lines += "attempt=$attempt"
        exception?.let {
            lines += "exception=${it::class.qualifiedName}: ${it.message}"
            lines += "stackTrace=${it.stackTraceToString().oneLine()}"
        }
        lines += ""
        events.forEach { event ->
            lines += event.toLogLine()
        }
        Files.writeString(logDirectory.resolve(attemptLogName(attempt)), lines.joinToString("\n") + "\n")
    }

    private fun writeSummary(logDirectory: Path, summary: List<String>) {
        Files.writeString(logDirectory.resolve("summary.log"), summary.joinToString("\n") + "\n")
    }

    private fun attemptLogName(attempt: Int): String =
        "attempt-${attempt.toString().padStart(3, '0')}.log"

    private fun configured(propertyName: String, environmentVariable: String): String? =
        System.getProperty(propertyName)?.takeIf { it.isNotBlank() }
            ?: System.getenv(environmentVariable)?.takeIf { it.isNotBlank() }

    private fun systemInt(propertyName: String, defaultValue: Int): Int =
        System.getProperty(propertyName)?.toIntOrNull()?.takeIf { it > 0 } ?: defaultValue

    private fun systemLong(propertyName: String, defaultValue: Long): Long =
        System.getProperty(propertyName)?.toLongOrNull()?.takeIf { it >= 0 } ?: defaultValue

    private fun systemBoolean(propertyName: String, defaultValue: Boolean): Boolean =
        System.getProperty(propertyName)?.toBooleanStrictOrNull() ?: defaultValue

    private fun String.oneLine(): String =
        replace("\r", "\\r").replace("\n", "\\n")
}
