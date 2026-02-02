package host.flux.maven.core

import mu.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path

private val logger = KotlinLogging.logger {}

/**
 * Detects the programming language of a Fluxzero project.
 */
object LanguageDetector {

    /**
     * Detects the language of the project at the given directory.
     */
    fun detect(projectDir: Path): Language {
        logger.debug { "Detecting language for project at: $projectDir" }

        // Check for Kotlin sources first
        val kotlinSrcDir = projectDir.resolve("src/main/kotlin")
        if (hasSourceFiles(kotlinSrcDir, ".kt")) {
            logger.info { "Detected Kotlin project (found .kt files)" }
            return Language.KOTLIN
        }

        // Check for Java sources
        val javaSrcDir = projectDir.resolve("src/main/java")
        if (hasSourceFiles(javaSrcDir, ".java")) {
            logger.info { "Detected Java project (found .java files)" }
            return Language.JAVA
        }

        // Fall back to build file detection
        val pomXml = projectDir.resolve("pom.xml")
        if (Files.exists(pomXml)) {
            val content = Files.readString(pomXml)
            return if (content.contains("kotlin-maven-plugin") || content.contains("<kotlin.")) {
                logger.info { "Detected Kotlin project (pom.xml with Kotlin plugin)" }
                Language.KOTLIN
            } else {
                logger.info { "Detected Java project (pom.xml)" }
                Language.JAVA
            }
        }

        val buildGradleKts = projectDir.resolve("build.gradle.kts")
        if (Files.exists(buildGradleKts)) {
            val content = Files.readString(buildGradleKts)
            return if (content.contains("kotlin(") || content.contains("kotlin.jvm")) {
                logger.info { "Detected Kotlin project (build.gradle.kts)" }
                Language.KOTLIN
            } else {
                logger.info { "Detected Java project (build.gradle.kts)" }
                Language.JAVA
            }
        }

        logger.warn { "Could not detect language, defaulting to Kotlin" }
        return Language.KOTLIN
    }

    private fun hasSourceFiles(directory: Path, extension: String): Boolean {
        if (!Files.exists(directory) || !Files.isDirectory(directory)) {
            return false
        }
        return Files.walk(directory)
            .filter { Files.isRegularFile(it) }
            .anyMatch { it.toString().endsWith(extension) }
    }
}
