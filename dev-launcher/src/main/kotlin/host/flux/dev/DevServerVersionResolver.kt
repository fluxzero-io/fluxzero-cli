package host.flux.dev

import org.w3c.dom.Element
import java.io.StringReader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

internal const val DEV_SERVER_GROUP_ID = "io.fluxzero.tools"
internal const val DEV_SERVER_ARTIFACT_ID = "fluxzero-dev-server"
internal const val SUPPORTED_DEV_SERVER_MAJOR = 1

class DevServerVersionResolver(
    private val metadataLoader: () -> String = { downloadMetadata() },
    private val messageSink: (String) -> Unit = { System.err.println(it) }
) {
    fun latestCompatible(projectDirectory: Path): String {
        val cache = projectDirectory.resolve(
            ".fluxzero/dev/launcher/latest-major-$SUPPORTED_DEV_SERVER_MAJOR.txt"
        )
        return try {
            latestCompatible(metadataLoader(), SUPPORTED_DEV_SERVER_MAJOR).also { version ->
                Files.createDirectories(cache.parent)
                writeAtomically(cache, version)
            }
        } catch (e: Exception) {
            cachedVersion(cache)?.also { version ->
                messageSink(
                    "Could not check Maven Central for Fluxzero dev server updates; using cached version $version."
                )
            } ?: throw IllegalStateException(
                "Could not determine the latest compatible Fluxzero dev server " +
                    "$SUPPORTED_DEV_SERVER_MAJOR.x release. Set --dev-server-version or " +
                    "FLUXZERO_DEV_SERVER_VERSION when working offline.",
                e
            )
        }
    }

    private fun cachedVersion(cache: Path): String? = runCatching {
        Files.readString(cache).trim().takeIf { version ->
            StableVersion.parse(version)?.major == SUPPORTED_DEV_SERVER_MAJOR
        }
    }.getOrNull()

    private fun writeAtomically(target: Path, value: String) {
        val temporary = Files.createTempFile(target.parent, target.fileName.toString(), ".tmp")
        Files.writeString(temporary, value)
        try {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        private const val METADATA_URL =
            "https://repo.maven.apache.org/maven2/io/fluxzero/tools/fluxzero-dev-server/maven-metadata.xml"

        internal fun latestCompatible(metadata: String, supportedMajor: Int): String {
            val factory = DocumentBuilderFactory.newInstance().apply {
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
                setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
                isXIncludeAware = false
                isExpandEntityReferences = false
            }
            val document = factory.newDocumentBuilder().parse(InputSource(StringReader(metadata)))
            val versions = document.getElementsByTagName("version")
            return (0 until versions.length)
                .mapNotNull { index -> (versions.item(index) as? Element)?.textContent?.trim() }
                .mapNotNull(StableVersion::parse)
                .filter { it.major == supportedMajor }
                .maxOrNull()
                ?.value
                ?: error("Maven metadata does not contain a stable $supportedMajor.x dev-server release")
        }

        private fun downloadMetadata(): String {
            val client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build()
            val request = HttpRequest.newBuilder(URI.create(METADATA_URL))
                .timeout(Duration.ofSeconds(3))
                .header("Accept", "application/xml")
                .GET()
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            check(response.statusCode() in 200..299) {
                "Maven Central metadata request failed with HTTP ${response.statusCode()}"
            }
            return response.body()
        }
    }

    private data class StableVersion(val value: String, val major: Int, val minor: Int, val patch: Int) :
        Comparable<StableVersion> {
        override fun compareTo(other: StableVersion): Int =
            compareValuesBy(this, other, StableVersion::major, StableVersion::minor, StableVersion::patch)

        companion object {
            private val pattern = Regex("(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)")

            fun parse(value: String): StableVersion? {
                val match = pattern.matchEntire(value) ?: return null
                return StableVersion(value, match.groupValues[1].toInt(), match.groupValues[2].toInt(),
                                     match.groupValues[3].toInt())
            }
        }
    }
}
