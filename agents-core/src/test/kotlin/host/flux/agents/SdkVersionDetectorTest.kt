package host.flux.agents

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SdkVersionDetectorTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `detects version from version catalog`() {
        val gradleDir = tempDir.resolve("gradle")
        Files.createDirectories(gradleDir)
        Files.writeString(
            gradleDir.resolve("libs.versions.toml"),
            """
            [versions]
            fluxzero = "1.2.3"
            kotlin = "2.1.20"

            [libraries]
            fluxzero-sdk = { module = "io.fluxzero:fluxzero-sdk", version.ref = "fluxzero" }
            """.trimIndent()
        )

        val result = SdkVersionDetector.detect(tempDir)

        assertEquals("1.2.3", result)
    }

    @Test
    fun `detects version from build gradle kts`() {
        Files.writeString(
            tempDir.resolve("build.gradle.kts"),
            """
            plugins {
                kotlin("jvm") version "2.1.20"
            }

            dependencies {
                implementation("io.fluxzero:fluxzero-sdk:2.0.0")
            }
            """.trimIndent()
        )

        val result = SdkVersionDetector.detect(tempDir)

        assertEquals("2.0.0", result)
    }

    @Test
    fun `detects version from pom xml with property`() {
        Files.writeString(
            tempDir.resolve("pom.xml"),
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <project>
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>my-app</artifactId>
                <version>1.0.0</version>
                <properties>
                    <fluxzero.version>3.0.0</fluxzero.version>
                </properties>
            </project>
            """.trimIndent()
        )

        val result = SdkVersionDetector.detect(tempDir)

        assertEquals("3.0.0", result)
    }

    @Test
    fun `detects version from pom xml with sdk property`() {
        Files.writeString(
            tempDir.resolve("pom.xml"),
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <project>
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>my-app</artifactId>
                <version>1.0.0</version>
                <properties>
                    <fluxzero-sdk.version>4.0.0</fluxzero-sdk.version>
                </properties>
            </project>
            """.trimIndent()
        )

        val result = SdkVersionDetector.detect(tempDir)

        assertEquals("4.0.0", result)
    }

    @Test
    fun `detects version from pom xml with direct dependency`() {
        Files.writeString(
            tempDir.resolve("pom.xml"),
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <project>
                <modelVersion>4.0.0</modelVersion>
                <dependencies>
                    <dependency>
                        <groupId>io.fluxzero</groupId>
                        <artifactId>fluxzero-sdk</artifactId>
                        <version>5.0.0</version>
                    </dependency>
                </dependencies>
            </project>
            """.trimIndent()
        )

        val result = SdkVersionDetector.detect(tempDir)

        assertEquals("5.0.0", result)
    }

    @Test
    fun `returns null when no sdk dependency found`() {
        Files.writeString(
            tempDir.resolve("build.gradle.kts"),
            """
            plugins {
                kotlin("jvm") version "2.1.20"
            }

            dependencies {
                implementation("org.example:some-lib:1.0.0")
            }
            """.trimIndent()
        )

        val result = SdkVersionDetector.detect(tempDir)

        assertNull(result)
    }

    @Test
    fun `returns null for empty directory`() {
        val result = SdkVersionDetector.detect(tempDir)

        assertNull(result)
    }

    @Test
    fun `version catalog takes precedence over build file`() {
        // Version catalog with 1.0.0
        val gradleDir = tempDir.resolve("gradle")
        Files.createDirectories(gradleDir)
        Files.writeString(
            gradleDir.resolve("libs.versions.toml"),
            """
            [versions]
            fluxzero = "1.0.0"
            """.trimIndent()
        )

        // Build file with 2.0.0
        Files.writeString(
            tempDir.resolve("build.gradle.kts"),
            """
            dependencies {
                implementation("io.fluxzero:fluxzero-sdk:2.0.0")
            }
            """.trimIndent()
        )

        val result = SdkVersionDetector.detect(tempDir)

        assertEquals("1.0.0", result)
    }

    @Test
    fun `handles api dependency type in gradle`() {
        Files.writeString(
            tempDir.resolve("build.gradle.kts"),
            """
            dependencies {
                api("io.fluxzero:fluxzero-sdk:1.5.0")
            }
            """.trimIndent()
        )

        val result = SdkVersionDetector.detect(tempDir)

        assertEquals("1.5.0", result)
    }

    @Test
    fun `detects version from pom xml with BOM dependency`() {
        Files.writeString(
            tempDir.resolve("pom.xml"),
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <project>
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>my-app</artifactId>
                <version>1.0.0</version>
                <dependencyManagement>
                    <dependencies>
                        <dependency>
                            <groupId>io.fluxzero</groupId>
                            <artifactId>fluxzero-bom</artifactId>
                            <version>1.73.0</version>
                            <scope>import</scope>
                            <type>pom</type>
                        </dependency>
                    </dependencies>
                </dependencyManagement>
            </project>
            """.trimIndent()
        )

        val result = SdkVersionDetector.detect(tempDir)

        assertEquals("1.73.0", result)
    }

    @Test
    fun `property version takes precedence over BOM dependency`() {
        Files.writeString(
            tempDir.resolve("pom.xml"),
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <project>
                <modelVersion>4.0.0</modelVersion>
                <properties>
                    <fluxzero.version>2.0.0</fluxzero.version>
                </properties>
                <dependencyManagement>
                    <dependencies>
                        <dependency>
                            <groupId>io.fluxzero</groupId>
                            <artifactId>fluxzero-bom</artifactId>
                            <version>1.73.0</version>
                            <scope>import</scope>
                            <type>pom</type>
                        </dependency>
                    </dependencies>
                </dependencyManagement>
            </project>
            """.trimIndent()
        )

        val result = SdkVersionDetector.detect(tempDir)

        // Property should take precedence
        assertEquals("2.0.0", result)
    }
}
