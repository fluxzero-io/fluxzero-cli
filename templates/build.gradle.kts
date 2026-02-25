plugins {
    kotlin("jvm")
}

dependencies {
    // YAML parsing for refactor.yaml files
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.21.1")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.21.1")

    // Testing
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.14.3")
    testImplementation("io.mockk:mockk:1.14.9")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
// Configuration for external examples; these are forwarded to the shell script
val examplesRepoUrl: String = (findProperty("examplesRepoUrl") as String?)
    ?: System.getenv("EXAMPLES_REPO_URL")
    ?: "https://github.com/fluxzero-io/fluxzero-examples.git"
val examplesReleaseTag: String = (findProperty("examplesReleaseTag") as String?)
    ?: System.getenv("EXAMPLES_RELEASE_TAG")
    ?: "latest"
val examplesZipUrl: String? = (findProperty("examplesZipUrl") as String?)
    ?: System.getenv("EXAMPLES_ZIP_URL")
val githubToken: String? = (findProperty("githubToken") as String?)
    ?: System.getenv("GITHUB_TOKEN")
val refreshExamples: Boolean = ((findProperty("refreshExamples") as String?)
    ?: System.getenv("REFRESH_EXAMPLES"))
    ?.toBoolean() ?: false

// Directories used by the script
val examplesWorkDir = layout.buildDirectory.dir("examples-snapshot")
val generatedTemplatesDir = layout.buildDirectory.dir("generated/resources/templates")

// Single task that calls the shell script to download, unpack, zip, and index templates
val packageTemplates by tasks.registering(Exec::class) {
    group = "build"
    description = "Download examples ZIP, unpack, repackage templates, and write templates.csv"

    val isWindows = System.getProperty("os.name").lowercase().contains("win")
    val bashScript = project.layout.projectDirectory.file("scripts/package_examples.sh").asFile
    val psScript = project.layout.projectDirectory.file("scripts/package_examples.ps1").asFile
    if (isWindows) {
        commandLine("pwsh", "-File", psScript.absolutePath)
    } else {
        commandLine("bash", bashScript.absolutePath)
    }
    workingDir = project.layout.projectDirectory.asFile

    // Provide environment variables for the script
    environment("EXAMPLES_REPO_URL", examplesRepoUrl)
    environment("EXAMPLES_RELEASE_TAG", examplesReleaseTag)
    if (examplesZipUrl != null) environment("EXAMPLES_ZIP_URL", examplesZipUrl)
    if (githubToken != null) environment("GITHUB_TOKEN", githubToken)
    if (refreshExamples) environment("REFRESH_EXAMPLES", "true")
    environment("CACHE_DIR", examplesWorkDir.get().asFile.absolutePath)
    environment("OUTPUT_DIR", generatedTemplatesDir.get().asFile.absolutePath)
    // Pass through PATH for tool resolution (zip/unzip/curl)
    System.getenv("PATH")?.let { environment("PATH", it) }
    // Enable debug logs in CI
    if (System.getenv("CI") != null) {
        environment("DEBUG_TEMPLATES", "true")
    }

    // Inputs/outputs for task tracking
    inputs.property("examplesRepoUrl", examplesRepoUrl)
    inputs.property("examplesReleaseTag", examplesReleaseTag)
    inputs.property("examplesZipUrl", examplesZipUrl ?: "")
    inputs.property("refreshExamples", refreshExamples)
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
