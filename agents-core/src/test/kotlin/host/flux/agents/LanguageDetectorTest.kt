package host.flux.agents

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals

class LanguageDetectorTest {

    @TempDir
    lateinit var tempDir: Path

    @BeforeEach
    fun setup() {
        // Clean state before each test
    }

    @Test
    fun `detects Kotlin when kotlin source files exist`() {
        // Create Kotlin source directory with a .kt file
        val kotlinSrcDir = tempDir.resolve("src/main/kotlin")
        Files.createDirectories(kotlinSrcDir)
        Files.writeString(kotlinSrcDir.resolve("Main.kt"), "fun main() {}")

        val result = LanguageDetector.detect(tempDir)

        assertEquals(Language.KOTLIN, result)
    }

    @Test
    fun `detects Java when java source files exist`() {
        // Create Java source directory with a .java file
        val javaSrcDir = tempDir.resolve("src/main/java")
        Files.createDirectories(javaSrcDir)
        Files.writeString(javaSrcDir.resolve("Main.java"), "public class Main {}")

        val result = LanguageDetector.detect(tempDir)

        assertEquals(Language.JAVA, result)
    }

    @Test
    fun `prefers Kotlin when both kotlin and java sources exist`() {
        // Create both source directories
        val kotlinSrcDir = tempDir.resolve("src/main/kotlin")
        Files.createDirectories(kotlinSrcDir)
        Files.writeString(kotlinSrcDir.resolve("Main.kt"), "fun main() {}")

        val javaSrcDir = tempDir.resolve("src/main/java")
        Files.createDirectories(javaSrcDir)
        Files.writeString(javaSrcDir.resolve("Helper.java"), "public class Helper {}")

        val result = LanguageDetector.detect(tempDir)

        assertEquals(Language.KOTLIN, result)
    }

    @Test
    fun `detects Kotlin from build gradle kts with kotlin plugin`() {
        Files.writeString(
            tempDir.resolve("build.gradle.kts"),
            """
            plugins {
                kotlin("jvm") version "2.1.20"
            }
            """.trimIndent()
        )

        val result = LanguageDetector.detect(tempDir)

        assertEquals(Language.KOTLIN, result)
    }

    @Test
    fun `detects Java from build gradle kts without kotlin plugin`() {
        Files.writeString(
            tempDir.resolve("build.gradle.kts"),
            """
            plugins {
                java
            }
            """.trimIndent()
        )

        val result = LanguageDetector.detect(tempDir)

        assertEquals(Language.JAVA, result)
    }

    @Test
    fun `detects Java from pom xml without kotlin`() {
        Files.writeString(
            tempDir.resolve("pom.xml"),
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <project>
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>my-app</artifactId>
                <version>1.0.0</version>
            </project>
            """.trimIndent()
        )

        val result = LanguageDetector.detect(tempDir)

        assertEquals(Language.JAVA, result)
    }

    @Test
    fun `detects Kotlin from pom xml with kotlin plugin`() {
        Files.writeString(
            tempDir.resolve("pom.xml"),
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <project>
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>my-app</artifactId>
                <version>1.0.0</version>
                <build>
                    <plugins>
                        <plugin>
                            <groupId>org.jetbrains.kotlin</groupId>
                            <artifactId>kotlin-maven-plugin</artifactId>
                        </plugin>
                    </plugins>
                </build>
            </project>
            """.trimIndent()
        )

        val result = LanguageDetector.detect(tempDir)

        assertEquals(Language.KOTLIN, result)
    }

    @Test
    fun `defaults to Kotlin when no indicators found`() {
        // Empty directory

        val result = LanguageDetector.detect(tempDir)

        assertEquals(Language.KOTLIN, result)
    }

    @Test
    fun `source files take precedence over build files`() {
        // Java source files
        val javaSrcDir = tempDir.resolve("src/main/java")
        Files.createDirectories(javaSrcDir)
        Files.writeString(javaSrcDir.resolve("Main.java"), "public class Main {}")

        // But Kotlin build file
        Files.writeString(
            tempDir.resolve("build.gradle.kts"),
            """
            plugins {
                kotlin("jvm") version "2.1.20"
            }
            """.trimIndent()
        )

        val result = LanguageDetector.detect(tempDir)

        // Source files should win
        assertEquals(Language.JAVA, result)
    }
}
