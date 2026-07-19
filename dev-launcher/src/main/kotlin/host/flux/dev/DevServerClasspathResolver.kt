package host.flux.dev

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

class DevServerClasspathResolver(
    private val executor: CommandExecutor,
    private val messageSink: (String) -> Unit = { System.err.println(it) }
) {
    fun resolve(projectDirectory: Path, version: String, reuseSnapshotCache: Boolean = false): String {
        val maven = Files.isRegularFile(projectDirectory.resolve("pom.xml"))
        val gradle = Files.isRegularFile(projectDirectory.resolve("build.gradle")) ||
            Files.isRegularFile(projectDirectory.resolve("build.gradle.kts")) ||
            Files.isRegularFile(projectDirectory.resolve("settings.gradle")) ||
            Files.isRegularFile(projectDirectory.resolve("settings.gradle.kts"))
        require(maven || gradle) { "No Maven or Gradle build found in $projectDirectory." }
        val launcherDirectory = projectDirectory.resolve(".fluxzero/dev/launcher")
        val classpathFile = launcherDirectory.resolve("classpath.txt")
        val versionFile = launcherDirectory.resolve("version")
        if ((!version.endsWith("SNAPSHOT") || reuseSnapshotCache)
            && isValidCache(classpathFile, versionFile, version)) {
            return Files.readString(classpathFile).trim()
        }

        Files.createDirectories(launcherDirectory)
        Files.deleteIfExists(classpathFile)
        messageSink("Resolving Fluxzero dev server $version...")
        val command = if (maven) {
            val launcherPom = launcherDirectory.resolve("pom.xml")
            writeAtomically(launcherPom, launcherPom(version))
            mavenCommand(projectDirectory) + listOf(
                "-q",
                "-f", launcherPom.toString(),
                "org.apache.maven.plugins:maven-dependency-plugin:3.11.0:build-classpath",
                "-Dmdep.includeScope=runtime",
                "-Dmdep.outputFile=${classpathFile.toAbsolutePath()}"
            )
        } else {
            writeAtomically(launcherDirectory.resolve("settings.gradle"),
                            "rootProject.name = 'fluxzero-dev-launcher'\n")
            writeAtomically(launcherDirectory.resolve("build.gradle"), launcherGradle(version))
            gradleCommand(projectDirectory) + listOf(
                "-q", "-p", launcherDirectory.toString(), "resolveFluxzeroDevServer",
                "-PfluxzeroDevClasspath=${classpathFile.toAbsolutePath()}",
                "--no-daemon",
                "--no-configuration-cache"
            )
        }
        val exitCode = executor.execute(command, projectDirectory, OutputMode.STDOUT_TO_STDERR)
        if (exitCode == 130 || exitCode == 143) {
            throw DevLaunchInterruptedException(exitCode)
        }
        check(exitCode == 0) {
            "Could not resolve Fluxzero dev server $version (${if (maven) "Maven" else "Gradle"} exit code $exitCode)."
        }
        check(Files.isRegularFile(classpathFile) && Files.readString(classpathFile).isNotBlank()) {
            "Maven did not produce a runtime classpath for Fluxzero dev server $version."
        }
        writeAtomically(versionFile, version)
        messageSink("")
        return Files.readString(classpathFile).trim()
    }

    internal fun mavenCommand(projectDirectory: Path): List<String> {
        val windows = System.getProperty("os.name").lowercase().contains("win")
        val wrapper = projectDirectory.resolve(if (windows) "mvnw.cmd" else "mvnw")
        return if (Files.isRegularFile(wrapper)) listOf(wrapper.toAbsolutePath().toString()) else listOf("mvn")
    }

    internal fun gradleCommand(projectDirectory: Path): List<String> {
        val windows = System.getProperty("os.name").lowercase().contains("win")
        val wrapper = projectDirectory.resolve(if (windows) "gradlew.bat" else "gradlew")
        return if (Files.isRegularFile(wrapper)) listOf(wrapper.toAbsolutePath().toString()) else listOf("gradle")
    }

    private fun isValidCache(classpathFile: Path, versionFile: Path, version: String): Boolean {
        if (!Files.isRegularFile(classpathFile) || !Files.isRegularFile(versionFile)) return false
        if (Files.readString(versionFile).trim() != version) return false
        val entries = Files.readString(classpathFile).trim().split(System.getProperty("path.separator"))
        return entries.isNotEmpty() && entries.all { it.isNotBlank() && Files.exists(Path.of(it)) }
    }

    private fun writeAtomically(target: Path, content: String) {
        Files.createDirectories(target.parent)
        val temporary = Files.createTempFile(target.parent, target.fileName.toString(), ".tmp")
        Files.writeString(temporary, content)
        try {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun launcherPom(version: String): String =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <project xmlns="http://maven.apache.org/POM/4.0.0">
          <modelVersion>4.0.0</modelVersion>
          <groupId>io.fluxzero.dev</groupId>
          <artifactId>dev-server-launcher</artifactId>
          <version>1</version>
          <dependencies>
            <dependency>
              <groupId>$DEV_SERVER_GROUP_ID</groupId>
              <artifactId>$DEV_SERVER_ARTIFACT_ID</artifactId>
              <version>${xml(version)}</version>
              <classifier>standalone</classifier>
              <exclusions>
                <exclusion>
                  <groupId>*</groupId>
                  <artifactId>*</artifactId>
                </exclusion>
              </exclusions>
            </dependency>
          </dependencies>
        </project>
        """.trimIndent()

    private fun launcherGradle(version: String): String =
        """
        repositories {
            mavenLocal()
            mavenCentral()
        }

        configurations {
            devServer
        }

        dependencies {
            devServer('$DEV_SERVER_GROUP_ID:$DEV_SERVER_ARTIFACT_ID:${groovy(version)}:standalone') {
                transitive = false
            }
        }

        tasks.register('resolveFluxzeroDevServer') {
            doLast {
                file(providers.gradleProperty('fluxzeroDevClasspath').get()).text =
                    configurations.devServer.asPath
            }
        }
        """.trimIndent()

    private fun xml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private fun groovy(value: String): String = value.replace("\\", "\\\\").replace("'", "\\'")
}

internal class DevLaunchInterruptedException(val exitCode: Int) : RuntimeException(null, null, false, false)
