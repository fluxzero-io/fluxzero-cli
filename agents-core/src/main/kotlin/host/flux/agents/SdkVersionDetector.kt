package host.flux.agents

import mu.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path

private val logger = KotlinLogging.logger {}

/**
 * Extracts the Fluxzero SDK version from build files.
 *
 * Supports:
 * - Gradle (build.gradle.kts, build.gradle)
 * - Maven (pom.xml)
 * - Version catalogs (gradle/libs.versions.toml)
 */
object SdkVersionDetector {

    private val SDK_ARTIFACT_ID = "fluxzero-sdk"
    private val BOM_ARTIFACT_ID = "fluxzero-bom"
    private val SDK_GROUP_ID = "io.fluxzero"

    // Patterns for different build systems
    private val VERSION_CATALOG_PATTERN = Regex("""fluxzero\s*=\s*["']([^"']+)["']""")
    private val GRADLE_DEPENDENCY_PATTERN = Regex(
        """(?:implementation|api|compile)\s*\(\s*["']$SDK_GROUP_ID:$SDK_ARTIFACT_ID:([^"']+)["']\s*\)"""
    )
    private val POM_VERSION_PATTERN = Regex(
        """<groupId>$SDK_GROUP_ID</groupId>\s*<artifactId>$SDK_ARTIFACT_ID</artifactId>\s*<version>([^<]+)</version>""",
        RegexOption.DOT_MATCHES_ALL
    )
    private val POM_BOM_VERSION_PATTERN = Regex(
        """<groupId>$SDK_GROUP_ID</groupId>\s*<artifactId>$BOM_ARTIFACT_ID</artifactId>\s*<version>([^<]+)</version>""",
        RegexOption.DOT_MATCHES_ALL
    )
    private val POM_PROPERTY_VERSION_PATTERN = Regex(
        """<fluxzero\.version>([^<]+)</fluxzero\.version>"""
    )
    private val POM_PROPERTY_SDK_VERSION_PATTERN = Regex(
        """<fluxzero-sdk\.version>([^<]+)</fluxzero-sdk\.version>"""
    )

    /**
     * Detects the SDK version from the project's build files.
     *
     * @param projectDir The root directory of the project
     * @return The detected SDK version, or null if not found
     */
    fun detect(projectDir: Path): String? {
        logger.debug { "Detecting SDK version for project at: $projectDir" }

        // Try version catalog first (most explicit)
        val versionCatalog = projectDir.resolve("gradle/libs.versions.toml")
        if (Files.exists(versionCatalog)) {
            val version = extractFromVersionCatalog(versionCatalog)
            if (version != null) {
                logger.info { "Found SDK version $version in version catalog" }
                return version
            }
        }

        // Try Gradle Kotlin DSL
        val buildGradleKts = projectDir.resolve("build.gradle.kts")
        if (Files.exists(buildGradleKts)) {
            val version = extractFromGradleFile(buildGradleKts)
            if (version != null) {
                logger.info { "Found SDK version $version in build.gradle.kts" }
                return version
            }
        }

        // Try Gradle Groovy DSL
        val buildGradle = projectDir.resolve("build.gradle")
        if (Files.exists(buildGradle)) {
            val version = extractFromGradleFile(buildGradle)
            if (version != null) {
                logger.info { "Found SDK version $version in build.gradle" }
                return version
            }
        }

        // Try Maven
        val pomXml = projectDir.resolve("pom.xml")
        if (Files.exists(pomXml)) {
            val version = extractFromPom(pomXml)
            if (version != null) {
                logger.info { "Found SDK version $version in pom.xml" }
                return version
            }
        }

        logger.warn { "Could not detect SDK version" }
        return null
    }

    private fun extractFromVersionCatalog(file: Path): String? {
        val content = Files.readString(file)
        return VERSION_CATALOG_PATTERN.find(content)?.groupValues?.get(1)
    }

    private fun extractFromGradleFile(file: Path): String? {
        val content = Files.readString(file)
        return GRADLE_DEPENDENCY_PATTERN.find(content)?.groupValues?.get(1)
    }

    private fun extractFromPom(file: Path): String? {
        val content = Files.readString(file)

        // Try property-based version first
        POM_PROPERTY_VERSION_PATTERN.find(content)?.let {
            return it.groupValues[1]
        }
        POM_PROPERTY_SDK_VERSION_PATTERN.find(content)?.let {
            return it.groupValues[1]
        }

        // Try direct version in SDK dependency
        POM_VERSION_PATTERN.find(content)?.let {
            return it.groupValues[1]
        }

        // Try BOM dependency (commonly used in dependencyManagement)
        return POM_BOM_VERSION_PATTERN.find(content)?.groupValues?.get(1)
    }
}
