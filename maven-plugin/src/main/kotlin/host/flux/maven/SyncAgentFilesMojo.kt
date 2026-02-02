package host.flux.maven

import host.flux.maven.core.AgentFilesService
import host.flux.maven.core.Language
import host.flux.maven.core.SyncResult
import org.apache.maven.plugin.AbstractMojo
import org.apache.maven.plugin.MojoExecutionException
import org.apache.maven.plugin.MojoFailureException
import org.apache.maven.plugins.annotations.LifecyclePhase
import org.apache.maven.plugins.annotations.Mojo
import org.apache.maven.plugins.annotations.Parameter
import java.io.File

/**
 * Maven Mojo that synchronizes AI agent files for Fluxzero projects.
 *
 * This mojo:
 * - Detects the SDK version from project dependencies
 * - Detects the project language (Kotlin or Java)
 * - Downloads the appropriate agent files from GitHub releases
 * - Extracts them to the project directory
 *
 * Usage in pom.xml:
 * ```xml
 * <plugin>
 *     <groupId>io.fluxzero.tools</groupId>
 *     <artifactId>fluxzero-maven-plugin</artifactId>
 *     <version>1.0.0</version>
 *     <executions>
 *         <execution>
 *             <goals>
 *                 <goal>sync-agent-files</goal>
 *             </goals>
 *         </execution>
 *     </executions>
 *     <configuration>
 *         <!-- Optional: override language detection -->
 *         <language>kotlin</language>
 *         <!-- Optional: override SDK version detection -->
 *         <sdkVersion>1.0.0</sdkVersion>
 *     </configuration>
 * </plugin>
 * ```
 *
 * Properties can also be set via command line:
 * ```
 * mvn fluxzero:sync-agent-files -Dfluxzero.agentFiles.language=kotlin
 * ```
 */
@Mojo(
    name = "sync-agent-files",
    defaultPhase = LifecyclePhase.INITIALIZE,
    threadSafe = true
)
class SyncAgentFilesMojo : AbstractMojo() {

    /**
     * The project base directory.
     */
    @Parameter(defaultValue = "\${project.basedir}", readonly = true)
    private lateinit var projectDir: File

    /**
     * The project language ("kotlin" or "java").
     * If not specified, auto-detected based on source files and build configuration.
     */
    @Parameter(property = "fluxzero.agentFiles.language")
    private var language: String? = null

    /**
     * Whether to force re-download of agent files even if they exist.
     */
    @Parameter(property = "fluxzero.agentFiles.forceUpdate", defaultValue = "false")
    private var forceUpdate: Boolean = false

    /**
     * Override the SDK version to use for fetching agent files.
     * If not specified, detected from project dependencies.
     */
    @Parameter(property = "fluxzero.agentFiles.sdkVersion")
    private var sdkVersion: String? = null

    /**
     * Whether to skip execution of this mojo.
     */
    @Parameter(property = "fluxzero.agentFiles.skip", defaultValue = "false")
    private var skip: Boolean = false

    override fun execute() {
        if (skip) {
            log.info("Skipping agent files sync (fluxzero.agentFiles.skip=true)")
            return
        }

        val lang = language?.let {
            Language.fromString(it)
                ?: throw MojoExecutionException("Invalid language: $it. Must be 'kotlin' or 'java'.")
        }

        log.info("Syncing Fluxzero agent files...")

        val service = AgentFilesService()
        val result = service.syncAgentFiles(
            projectDir = projectDir.toPath(),
            forceUpdate = forceUpdate,
            language = lang,
            version = sdkVersion
        )

        when (result) {
            is SyncResult.Updated -> {
                log.info("Agent files updated to version ${result.version}")
                log.debug("Files written: ${result.filesWritten.joinToString(", ")}")
            }
            is SyncResult.UpToDate -> {
                log.info("Agent files are up to date (version ${result.version})")
            }
            is SyncResult.Skipped -> {
                log.warn("Agent files sync skipped: ${result.reason}")
            }
            is SyncResult.Failed -> {
                throw MojoFailureException("Failed to sync agent files: ${result.error}", result.cause)
            }
        }
    }
}
