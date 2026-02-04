package host.flux.projectfiles

import mu.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path

private val logger = KotlinLogging.logger {}

/**
 * Detects the programming language of a Fluxzero project.
 *
 * Detection strategy:
 * 1. Check for Kotlin source directory (src/main/kotlin)
 * 2. Check for Java source directory (src/main/java)
 * 3. Fall back to build file type (build.gradle.kts = Kotlin, pom.xml = Java)
 */
object LanguageDetector {

    /**
     * Detects the language of the project at the given directory.
     *
     * @param projectDir The root directory of the project
     * @return The detected language, defaulting to KOTLIN if unable to determine
     */
    fun detect(projectDir: Path): Language {
        logger.debug { "Detecting language for project at: $projectDir" }

        // Check for Kotlin sources first
        val kotlinSrcDir = projectDir.resolve("src/main/kotlin")
        if (hasSourceFiles(kotlinSrcDir, ".kt")) {
            logger.info { "Detected Kotlin project (found .kt files in src/main/kotlin)" }
            return Language.KOTLIN
        }

        // Check for Java sources
        val javaSrcDir = projectDir.resolve("src/main/java")
        if (hasSourceFiles(javaSrcDir, ".java")) {
            logger.info { "Detected Java project (found .java files in src/main/java)" }
            return Language.JAVA
        }

        // Fall back to build file detection
        val buildGradleKts = projectDir.resolve("build.gradle.kts")
        val buildGradle = projectDir.resolve("build.gradle")
        val pomXml = projectDir.resolve("pom.xml")

        return when {
            Files.exists(buildGradleKts) -> {
                // Check if build.gradle.kts contains kotlin plugin
                val content = Files.readString(buildGradleKts)
                if (content.contains("kotlin(") || content.contains("kotlin.jvm")) {
                    logger.info { "Detected Kotlin project (build.gradle.kts with Kotlin plugin)" }
                    Language.KOTLIN
                } else {
                    logger.info { "Detected Java project (build.gradle.kts without Kotlin plugin)" }
                    Language.JAVA
                }
            }
            Files.exists(buildGradle) -> {
                val content = Files.readString(buildGradle)
                if (content.contains("kotlin") || content.contains("'org.jetbrains.kotlin")) {
                    logger.info { "Detected Kotlin project (build.gradle with Kotlin plugin)" }
                    Language.KOTLIN
                } else {
                    logger.info { "Detected Java project (build.gradle without Kotlin plugin)" }
                    Language.JAVA
                }
            }
            Files.exists(pomXml) -> {
                val content = Files.readString(pomXml)
                if (content.contains("kotlin-maven-plugin") || content.contains("<kotlin.")) {
                    logger.info { "Detected Kotlin project (pom.xml with Kotlin plugin)" }
                    Language.KOTLIN
                } else {
                    logger.info { "Detected Java project (pom.xml)" }
                    Language.JAVA
                }
            }
            else -> {
                logger.warn { "Could not detect language, defaulting to Kotlin" }
                Language.KOTLIN
            }
        }
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
