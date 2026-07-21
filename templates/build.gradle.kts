import org.gradle.api.tasks.PathSensitivity

plugins {
    kotlin("jvm")
}

dependencies {
    // YAML parsing for refactor.yaml files
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.17.0")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.0")

    // Testing
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
    testImplementation("io.mockk:mockk:1.14.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Configuration for the separately released template repository.
val templatesRepoUrl: String = (findProperty("templatesRepoUrl") as String?)
    ?: System.getenv("TEMPLATES_REPO_URL")
    ?: "https://github.com/fluxzero-io/fluxzero-templates.git"
val templatesReleaseTag: String = (findProperty("templatesReleaseTag") as String?)
    ?: System.getenv("TEMPLATES_RELEASE_TAG")
    ?: "latest"
val templatesZipUrl: String? = (findProperty("templatesZipUrl") as String?)
    ?: System.getenv("TEMPLATES_ZIP_URL")
val configuredTemplatesSourceDir: String? = (findProperty("templatesSourceDir") as String?)
    ?: System.getenv("TEMPLATES_SOURCE_DIR")
val defaultLocalTemplatesDir = rootProject.layout.projectDirectory.dir("../fluxzero-templates").asFile
val templatesSourceDir = configuredTemplatesSourceDir
    ?.let { file(it) }
    ?: defaultLocalTemplatesDir.takeIf { it.isDirectory && it.resolve("flux-basic-java").isDirectory }
val githubToken: String? = (findProperty("githubToken") as String?)
    ?: System.getenv("GITHUB_TOKEN")
val refreshTemplates: Boolean = ((findProperty("refreshTemplates") as String?)
    ?: System.getenv("REFRESH_TEMPLATES"))
    ?.toBoolean() ?: false

// Directories used by the script
val templatesWorkDir = layout.buildDirectory.dir("templates-snapshot")
val generatedTemplatesDir = layout.buildDirectory.dir("generated/resources/templates")

// Single task that calls the shell script to download, unpack, zip, and index templates
val packageTemplates by tasks.registering(Exec::class) {
    group = "build"
    description = "Download templates ZIP, unpack, repackage templates, and write templates.csv"

    val isWindows = System.getProperty("os.name").lowercase().contains("win")
    val bashScript = project.layout.projectDirectory.file("scripts/package_templates.sh").asFile
    val psScript = project.layout.projectDirectory.file("scripts/package_templates.ps1").asFile
    if (isWindows) {
        commandLine("pwsh", "-File", psScript.absolutePath)
    } else {
        commandLine("bash", bashScript.absolutePath)
    }
    workingDir = project.layout.projectDirectory.asFile

    // Provide environment variables for the script
    environment("TEMPLATES_REPO_URL", templatesRepoUrl)
    environment("TEMPLATES_RELEASE_TAG", templatesReleaseTag)
    if (templatesZipUrl != null) environment("TEMPLATES_ZIP_URL", templatesZipUrl)
    if (templatesSourceDir != null) environment("TEMPLATES_SOURCE_DIR", templatesSourceDir.absolutePath)
    if (githubToken != null) environment("GITHUB_TOKEN", githubToken)
    if (refreshTemplates) environment("REFRESH_TEMPLATES", "true")
    environment("CACHE_DIR", templatesWorkDir.get().asFile.absolutePath)
    environment("OUTPUT_DIR", generatedTemplatesDir.get().asFile.absolutePath)
    // Pass through PATH for tool resolution (zip/unzip/curl)
    System.getenv("PATH")?.let { environment("PATH", it) }
    // Enable debug logs in CI
    if (System.getenv("CI") != null) {
        environment("DEBUG_TEMPLATES", "true")
    }

    // Inputs/outputs for task tracking
    inputs.property("templatesRepoUrl", templatesRepoUrl)
    inputs.property("templatesReleaseTag", templatesReleaseTag)
    inputs.property("templatesZipUrl", templatesZipUrl ?: "")
    inputs.property("templatesSourceDir", templatesSourceDir?.absolutePath ?: "")
    if (templatesSourceDir != null) {
        inputs.files(fileTree(templatesSourceDir) {
            exclude(
                "**/.git/**",
                "**/.gradle/**",
                "**/.idea/**",
                "**/build/**",
                "**/target/**",
                "**/.DS_Store"
            )
        })
            .withPropertyName("templatesSourceFiles")
            .withPathSensitivity(PathSensitivity.RELATIVE)
    }
    inputs.property("refreshTemplates", refreshTemplates)
    outputs.dir(generatedTemplatesDir)
}

sourceSets {
    main {
        resources {
            srcDir("build/generated/resources")
        }
    }
}

tasks.named("processResources") {
    dependsOn(packageTemplates)
}

tasks.test {
    useJUnitPlatform()
}
