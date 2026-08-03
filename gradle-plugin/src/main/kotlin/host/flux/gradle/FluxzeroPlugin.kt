package host.flux.gradle

import groovy.json.JsonOutput
import host.flux.publishing.PackageNameSupport
import java.io.File
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.findByType
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType

/**
 * Gradle plugin for Fluxzero projects.
 *
 * Features:
 * - **Project Files Sync**: Automatically syncs AI assistant instruction files
 *
 * Usage in build.gradle.kts:
 * ```kotlin
 * plugins {
 *     id("io.fluxzero.tools.gradle.plugin") version "1.0.0"
 * }
 *
 * // Minimal config - everything is auto-detected:
 * fluxzero {
 *     projectFiles {
 *         enabled.set(true)  // default
 *     }
 * }
 *
 * // Or with overrides if auto-detection fails:
 * fluxzero {
 *     projectFiles {
 *         overrideLanguage.set("kotlin")
 *         overrideSdkVersion.set("1.0.0")
 *     }
 * }
 * ```
 */
class FluxzeroPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        // Create the main extension
        val extension = project.extensions.create<FluxzeroExtension>("fluxzero")

        // Set default values for project files feature
        extension.projectFiles.enabled.convention(true)
        extension.projectFiles.forceUpdate.convention(false)
        extension.projectFiles.rootProjectOnly.convention(true)

        // Register project files sync feature
        registerProjectFilesFeature(project, extension)
        registerDevFeature(project, extension)
        registerPackagePublishingFeature(project, extension)
    }

    private fun registerPackagePublishingFeature(project: Project, extension: FluxzeroExtension) {
        val publishing = extension.packagePublishing
        publishing.packageName.convention(project.name)
        publishing.packageVersion.convention(project.provider {
            PackageNameSupport.defaultPackageVersion(project.version.toString())
        })
        publishing.images.convention(emptyList())
        publishing.tags.convention(emptyList())
        publishing.labels.convention(emptyMap())

        val publishTask = project.tasks.register<PublishPackageTask>("fluxzeroPublishPackage") {
            group = "fluxzero"
            description = "Builds and publishes a layered Java OCI package."
            packageName.set(publishing.packageName)
            packageVersion.set(publishing.packageVersion)
            applicationId.set(publishing.applicationId)
            mainClass.set(publishing.mainClass)
            images.set(publishing.images)
            tags.set(publishing.tags)
            baseImage.set(publishing.baseImage)
            baseImageSource.set(publishing.baseImageSource)
            javaToolOptions.set(publishing.javaToolOptions)
            labels.set(publishing.labels)
            defaultLabels.set(project.provider { gradlePackageLabels(project) })
            publishAttempts.set(publishing.publishAttempts)
            publishRetryDelayMillis.set(publishing.publishRetryDelayMillis)
            authentications = publishing.authentications
        }

        project.plugins.withType<JavaPlugin> {
            val sourceSets = project.extensions.getByType(SourceSetContainer::class.java)
            val main = sourceSets.getByName("main")
            publishTask.configure { task ->
                task.dependsOn(project.tasks.named(main.classesTaskName))
                task.classesDirectories.from(main.output)
                task.runtimeClasspath.from(main.runtimeClasspath)
            }
            val jarTask = project.tasks.named("jar", AbstractArchiveTask::class.java)
            publishTask.configure { task ->
                task.applicationArtifact.convention(jarTask.flatMap { it.archiveFile })
                task.dependsOn(jarTask)
            }
            project.pluginManager.withPlugin("org.springframework.boot") {
                val bootJarTask = project.tasks.named("bootJar", AbstractArchiveTask::class.java)
                publishTask.configure { task ->
                    task.applicationArtifact.set(bootJarTask.flatMap { it.archiveFile })
                    task.dependsOn(bootJarTask)
                }
            }
        }
    }

    private fun gradlePackageLabels(project: Project): Map<String, String> = buildMap {
        put("io.fluxzero.gradle.group", project.group.toString())
        put("io.fluxzero.gradle.project-name", project.name)
        put("io.fluxzero.gradle.version", project.version.toString())
        val gitInfo = PackageNameSupport.gitInfo(project.rootProject.projectDir.toPath())
        gitInfo?.sha?.let { put("org.opencontainers.image.revision", it) }
        val githubRepository = System.getenv("GITHUB_REPOSITORY")?.takeIf(String::isNotBlank)
        if (githubRepository != null) {
            val serverUrl = System.getenv("GITHUB_SERVER_URL")?.takeIf(String::isNotBlank)
                ?: "https://github.com"
            put("org.opencontainers.image.source", "${serverUrl.trimEnd('/')}/$githubRepository")
        } else {
            gitInfo?.remoteUrl?.let { put("org.opencontainers.image.source", it) }
        }
    }

    private fun registerDevFeature(project: Project, extension: FluxzeroExtension) {
        val metadata = project.tasks.register<DefaultTask>("fluxzeroDevMetadata") {
            group = "fluxzero"
            description = "Compiles applications and writes classpath metadata for the Fluxzero dev server."
            doLast {
                val modules = participatingProjects(project).mapNotNull { module ->
                    val sourceSets = module.extensions.findByType<SourceSetContainer>() ?: return@mapNotNull null
                    val main = sourceSets.findByName("main") ?: return@mapNotNull null
                    val test = sourceSets.findByName("test")
                    val mainOutput = main.output.files.filter(File::exists).map(File::getAbsolutePath)
                    val testOutput = test?.output?.files.orEmpty()
                        .filter(File::exists).map(File::getAbsolutePath)
                    val allOutput = (main.output.files + test?.output?.files.orEmpty()).toSet()
                    val runtimeFiles = main.runtimeClasspath.files.filter(File::exists)
                    val testRuntimeFiles = test?.runtimeClasspath?.files.orEmpty().filter(File::exists)
                    mapOf(
                        "path" to modulePath(project, module),
                        "name" to module.name,
                        "mainClasses" to mainOutput,
                        "testClasses" to testOutput,
                        "runtimeDirectories" to runtimeFiles.filter(File::isDirectory)
                            .map(File::getAbsolutePath),
                        "testRuntimeDirectories" to testRuntimeFiles.filter(File::isDirectory)
                            .map(File::getAbsolutePath),
                        "runtimeClasspath" to runtimeFiles
                            .filter { it.isFile && it !in allOutput }.map(File::getAbsolutePath),
                        "testRuntimeClasspath" to testRuntimeFiles
                            .filter { it.isFile && it !in allOutput }.map(File::getAbsolutePath)
                    )
                }
                val target = project.rootProject.layout.projectDirectory
                    .file(".fluxzero/dev/gradle-metadata.json").asFile
                target.parentFile.mkdirs()
                target.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(mapOf("modules" to modules))))
            }
        }
        val devTests = project.tasks.register<DefaultTask>("fluxzeroDevTest") {
            group = "verification"
            description = "Runs tests selected by the Fluxzero dev server."
        }

        project.gradle.projectsEvaluated {
            participatingProjects(project).forEach { module ->
                module.tasks.findByName("classes")?.let { dependency ->
                    metadata.configure { metadataTask -> metadataTask.dependsOn(dependency) }
                }
                module.tasks.findByName("testClasses")?.let { dependency ->
                    metadata.configure { metadataTask -> metadataTask.dependsOn(dependency) }
                }
                module.tasks.withType<Test>().forEach { testTask ->
                    devTests.configure { aggregate -> aggregate.dependsOn(testTask) }
                }
            }
        }

        project.tasks.register<FluxzeroDevTask>("fluxzeroDev") {
            group = "fluxzero"
            description = "Starts the local Fluxzero development environment."
            projectDirectory.set(project.rootProject.layout.projectDirectory)
            serverVersion.set(project.stringGradleProperty("fluxzero.dev.serverVersion").orElse(extension.dev.serverVersion))
            mainClass.set(project.stringGradleProperty("fluxzero.dev.mainClass").orElse(extension.dev.mainClass))
            applicationName.set(project.stringGradleProperty("fluxzero.dev.applicationName").orElse(extension.dev.applicationName))
            applications.set(project.listGradleProperty("fluxzero.dev.applications").orElse(extension.dev.applications))
            profile.set(project.stringGradleProperty("fluxzero.dev.profile").orElse(extension.dev.profile))
            environment.set(project.stringGradleProperty("fluxzero.dev.environment").orElse(extension.dev.environment))
            port.set(project.intGradleProperty("fluxzero.dev.port").orElse(extension.dev.port))
            idp.set(project.stringGradleProperty("fluxzero.dev.idp").orElse(extension.dev.idp))
            namespace.set(project.stringGradleProperty("fluxzero.dev.namespace").orElse(extension.dev.namespace))
            watch.set(project.booleanGradleProperty("fluxzero.dev.watch").orElse(extension.dev.watch))
            compileOnStart.set(project.booleanGradleProperty("fluxzero.dev.compileOnStart").orElse(extension.dev.compileOnStart))
            testsEnabled.set(project.booleanGradleProperty("fluxzero.dev.testsEnabled").orElse(extension.dev.testsEnabled))
            fastCompiler.set(project.booleanGradleProperty("fluxzero.dev.fastCompiler").orElse(extension.dev.fastCompiler))
            frontendCommand.set(project.stringGradleProperty("fluxzero.dev.frontendCommand").orElse(extension.dev.frontendCommand))
            frontendDirectory.set(project.stringGradleProperty("fluxzero.dev.frontendDirectory").orElse(extension.dev.frontendDirectory))
            frontendSetupCommand.set(project.stringGradleProperty("fluxzero.dev.frontendSetupCommand").orElse(extension.dev.frontendSetupCommand))
            frontendUrl.set(project.stringGradleProperty("fluxzero.dev.frontendUrl").orElse(extension.dev.frontendUrl))
            frontendEnabled.set(project.booleanGradleProperty("fluxzero.dev.frontendEnabled").orElse(extension.dev.frontendEnabled))
            backendPaths.set(project.listGradleProperty("fluxzero.dev.backendPaths").orElse(extension.dev.backendPaths))
            appArgs.set(project.listGradleProperty("fluxzero.dev.appArgs").orElse(extension.dev.appArgs))
            startupTimeoutMillis.set(project.longGradleProperty("fluxzero.dev.startupTimeoutMillis").orElse(extension.dev.startupTimeoutMillis))
            gracefulShutdownTimeoutMillis.set(project.longGradleProperty("fluxzero.dev.gracefulShutdownTimeoutMillis").orElse(extension.dev.gracefulShutdownTimeoutMillis))
            debounceMillis.set(project.longGradleProperty("fluxzero.dev.debounceMillis").orElse(extension.dev.debounceMillis))
            background.set(project.booleanGradleProperty("fluxzero.dev.background").orElse(extension.dev.background))
        }

        participatingProjects(project).forEach { module ->
            module.tasks.withType<Test>().configureEach { testTask ->
                testTask.doFirst {
                    module.findProperty("fluxzero.dev.testSelectors")?.toString()
                        ?.split(',')?.map(String::trim)?.filter(String::isNotEmpty)?.forEach { selector ->
                            testTask.filter.includeTestsMatching(selector.replace('#', '.'))
                        }
                    module.findProperty("fluxzero.testImpact.enabled")?.toString()?.let {
                        testTask.systemProperty("fluxzero.testImpact.enabled", it)
                    }
                    module.findProperty("fluxzero.testImpact.directory")?.toString()?.let {
                        testTask.systemProperty("fluxzero.testImpact.directory", it)
                    }
                }
            }
        }
    }

    private fun participatingProjects(project: Project): List<Project> =
        if (project == project.rootProject) project.allprojects.toList() else listOf(project)

    private fun modulePath(pluginProject: Project, module: Project): String =
        if (module == pluginProject.rootProject) "." else module.path.removePrefix(":").replace(':', '/')

    private fun registerProjectFilesFeature(project: Project, extension: FluxzeroExtension) {
        val projectFiles = extension.projectFiles
        val enabled = project.booleanGradleProperty("fluxzero.projectFiles.enabled").orElse(projectFiles.enabled)
        val rootProjectOnly = project.booleanGradleProperty("fluxzero.projectFiles.rootProjectOnly").orElse(projectFiles.rootProjectOnly)
        val forceUpdate = project.booleanGradleProperty("fluxzero.projectFiles.forceUpdate").orElse(projectFiles.forceUpdate)
        val overrideSdkVersion = project.providers.gradleProperty("fluxzero.projectFiles.overrideSdkVersion").orElse(projectFiles.overrideSdkVersion)
        val overrideLanguage = project.providers.gradleProperty("fluxzero.projectFiles.overrideLanguage").orElse(projectFiles.overrideLanguage)

        // Register the sync task
        val syncTask = project.tasks.register<SyncProjectFilesTask>("syncProjectFiles") {
            // Configure task only if feature is enabled and (not rootProjectOnly or this is root project)
            onlyIf {
                enabled.get() &&
                    (!rootProjectOnly.get() || project == project.rootProject)
            }

            // Set up project directory
            projectDir.set(project.layout.projectDirectory)

            // Set up output directory for tracking changes
            projectFilesDir.set(project.layout.projectDirectory.dir(".fluxzero/agents"))

            // Configure version - use override value or auto-detect
            sdkVersion.set(project.provider {
                overrideSdkVersion.orNull
                    ?: SyncProjectFilesTask.detectSdkVersion(project.projectDir)
            })

            // Configure language - use override value or auto-detect
            language.set(project.provider {
                overrideLanguage.orNull
                    ?: SyncProjectFilesTask.detectLanguage(project.projectDir)
            })

            // Configure force update
            this.forceUpdate.set(forceUpdate)
        }

        // Hook into the build lifecycle - run before compilation
        project.plugins.withType<JavaPlugin> {
            project.tasks.named("compileJava") {
                it.dependsOn(syncTask)
            }
        }

        // Also hook into Kotlin compilation if present
        project.pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
            project.tasks.named("compileKotlin") {
                it.dependsOn(syncTask)
            }
        }

        // Log configuration at the end of evaluation
        project.afterEvaluate {
            if (enabled.get()) {
                val version = overrideSdkVersion.orNull
                    ?: SyncProjectFilesTask.detectSdkVersion(project.projectDir)
                val lang = overrideLanguage.orNull
                    ?: SyncProjectFilesTask.detectLanguage(project.projectDir)

                if (version == "unknown") {
                    project.logger.info(
                        "Fluxzero project files: No SDK version detected. " +
                            "Project files will not be synced unless overrideSdkVersion is set."
                    )
                } else {
                    project.logger.info(
                        "Fluxzero project files configured: version=$version, language=$lang"
                    )
                }
            } else {
                project.logger.info("Fluxzero project files sync is disabled")
            }
        }
    }

    private fun Project.booleanGradleProperty(name: String): Provider<Boolean> =
        providers.gradleProperty(name).map { value ->
            value.toBooleanStrictOrNull()
                ?: throw GradleException("Invalid value for -P$name=$value. Must be 'true' or 'false'.")
        }

    private fun Project.stringGradleProperty(name: String): Provider<String> =
        providers.gradleProperty(name).map(String::trim).map { value ->
            value.takeIf(String::isNotEmpty) ?: throw GradleException("-P$name must not be blank")
        }

    private fun Project.intGradleProperty(name: String): Provider<Int> =
        providers.gradleProperty(name).map { value ->
            value.toIntOrNull() ?: throw GradleException("Invalid value for -P$name=$value. Must be an integer.")
        }

    private fun Project.longGradleProperty(name: String): Provider<Long> =
        providers.gradleProperty(name).map { value ->
            value.toLongOrNull() ?: throw GradleException("Invalid value for -P$name=$value. Must be an integer.")
        }

    private fun Project.listGradleProperty(name: String): Provider<List<String>> =
        providers.gradleProperty(name).map { value ->
            value.split(',').map(String::trim).filter(String::isNotEmpty)
        }
}
