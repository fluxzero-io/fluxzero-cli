package host.flux.maven

import host.flux.agents.DefaultAgentFilesService
import host.flux.agents.Language
import host.flux.agents.SyncResult
import org.apache.maven.execution.MavenSession
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
 * This mojo automatically:
 * - Detects the SDK version from project dependencies
 * - Detects the project language (Kotlin or Java)
 * - Downloads the appropriate agent files from GitHub releases
 * - Extracts them to the project directory
 *
 * Usage in pom.xml (minimal - everything auto-detected):
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
 * </plugin>
 * ```
 *
 * With overrides (only if auto-detection fails):
 * ```xml
 * <configuration>
 *     <overrideLanguage>kotlin</overrideLanguage>
 *     <overrideSdkVersion>1.0.0</overrideSdkVersion>
 * </configuration>
 * ```
 *
 * Properties can also be set via command line:
 * ```
 * mvn fluxzero:sync-agent-files -Dfluxzero.agentFiles.overrideLanguage=kotlin
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
     * The Maven session, used to determine the execution root directory.
     */
    @Parameter(defaultValue = "\${session}", readonly = true)
    private lateinit var session: MavenSession

    /**
     * Whether to only run on the root project in a multi-module build.
     * When true (default), submodules will skip agent files sync.
     * Set to false to run on every module.
     */
    @Parameter(property = "fluxzero.agentFiles.rootProjectOnly", defaultValue = "true")
    private var rootProjectOnly: Boolean = true

    /**
     * Override the auto-detected language ("kotlin" or "java").
     * Only set this if auto-detection fails or returns the wrong language.
     */
    @Parameter(property = "fluxzero.agentFiles.overrideLanguage")
    private var overrideLanguage: String? = null

    /**
     * Whether to force re-download of agent files even if they exist.
     */
    @Parameter(property = "fluxzero.agentFiles.forceUpdate", defaultValue = "false")
    private var forceUpdate: Boolean = false

    /**
     * Override the auto-detected SDK version.
     * Only set this if auto-detection fails or you need a specific version.
     */
    @Parameter(property = "fluxzero.agentFiles.overrideSdkVersion")
    private var overrideSdkVersion: String? = null

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

        if (rootProjectOnly && !isRootProject()) {
            log.debug("Skipping agent files sync for non-root module: ${projectDir.name}")
            return
        }

        val lang = overrideLanguage?.let {
            Language.fromString(it)
                ?: throw MojoExecutionException("Invalid language: $it. Must be 'kotlin' or 'java'.")
        }

        log.info("Syncing Fluxzero agent files...")

        val service = DefaultAgentFilesService()
        val result = service.syncAgentFiles(
            projectDir = projectDir.toPath(),
            forceUpdate = forceUpdate,
            language = lang,
            version = overrideSdkVersion
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

    /**
     * Checks if the current project is the root project (execution root) in a multi-module build.
     */
    private fun isRootProject(): Boolean {
        val executionRootDir = File(session.executionRootDirectory)
        return projectDir.canonicalPath == executionRootDir.canonicalPath
    }
}
