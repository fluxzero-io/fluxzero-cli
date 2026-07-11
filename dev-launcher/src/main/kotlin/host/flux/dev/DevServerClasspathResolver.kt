package host.flux.dev

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

class DevServerClasspathResolver(
    private val executor: CommandExecutor,
    private val messageSink: (String) -> Unit = { System.err.println(it) }
) {
    fun resolve(projectDirectory: Path, version: String): String {
        require(Files.isRegularFile(projectDirectory.resolve("pom.xml"))) {
            "No pom.xml found in $projectDirectory. Fluxzero dev currently supports Maven projects."
        }
        val launcherDirectory = projectDirectory.resolve(".fluxzero/dev/launcher")
        val classpathFile = launcherDirectory.resolve("classpath.txt")
        val versionFile = launcherDirectory.resolve("version")
        if (!version.endsWith("SNAPSHOT") && isValidCache(classpathFile, versionFile, version)) {
            return Files.readString(classpathFile).trim()
        }

        Files.createDirectories(launcherDirectory)
        val launcherPom = launcherDirectory.resolve("pom.xml")
        writeAtomically(launcherPom, launcherPom(version))
        Files.deleteIfExists(classpathFile)
        messageSink("Resolving Fluxzero dev server $version...")
        val command = mavenCommand(projectDirectory) + listOf(
            "-q",
            "-f", launcherPom.toString(),
            "org.apache.maven.plugins:maven-dependency-plugin:3.11.0:build-classpath",
            "-Dmdep.includeScope=runtime",
            "-Dmdep.outputFile=${classpathFile.toAbsolutePath()}"
        )
        val exitCode = executor.execute(command, projectDirectory, OutputMode.STDOUT_TO_STDERR)
        if (exitCode == 130 || exitCode == 143) {
            throw DevLaunchInterruptedException(exitCode)
        }
        check(exitCode == 0) { "Could not resolve Fluxzero dev server $version (Maven exit code $exitCode)." }
        check(Files.isRegularFile(classpathFile) && Files.readString(classpathFile).isNotBlank()) {
            "Maven did not produce a runtime classpath for Fluxzero dev server $version."
        }
        writeAtomically(versionFile, version)
        return Files.readString(classpathFile).trim()
    }

    internal fun mavenCommand(projectDirectory: Path): List<String> {
        val windows = System.getProperty("os.name").lowercase().contains("win")
        val wrapper = projectDirectory.resolve(if (windows) "mvnw.cmd" else "mvnw")
        return if (Files.isRegularFile(wrapper)) listOf(wrapper.toAbsolutePath().toString()) else listOf("mvn")
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
              <groupId>io.fluxzero</groupId>
              <artifactId>dev-server</artifactId>
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

    private fun xml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}

internal class DevLaunchInterruptedException(val exitCode: Int) : RuntimeException(null, null, false, false)
