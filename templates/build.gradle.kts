plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(21)
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
// Configuration for external examples; these are forwarded to the shell script
val examplesRepoUrl: String = (findProperty("examplesRepoUrl") as String?)
    ?: System.getenv("EXAMPLES_REPO_URL")
    ?: "https://github.com/fluxzero-io/fluxzero-examples.git"
val examplesBranch: String = (findProperty("examplesBranch") as String?)
    ?: System.getenv("EXAMPLES_BRANCH")
    ?: "main"
val examplesZipUrl: String? = (findProperty("examplesZipUrl") as String?)
    ?: System.getenv("EXAMPLES_ZIP_URL")
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

    val script = project.layout.projectDirectory.file("scripts/package_examples.sh").asFile
    commandLine("bash", script.absolutePath)
    workingDir = project.layout.projectDirectory.asFile

    // Provide environment variables for the script
    environment("EXAMPLES_REPO_URL", examplesRepoUrl)
    environment("EXAMPLES_BRANCH", examplesBranch)
    if (examplesZipUrl != null) environment("EXAMPLES_ZIP_URL", examplesZipUrl)
    if (refreshExamples) environment("REFRESH_EXAMPLES", "true")
    environment("CACHE_DIR", examplesWorkDir.get().asFile.absolutePath)
    environment("OUTPUT_DIR", generatedTemplatesDir.get().asFile.absolutePath)
    // Pass through PATH for tool resolution (zip/unzip/curl)
    System.getenv("PATH")?.let { environment("PATH", it) }

    // Inputs/outputs for task tracking
    inputs.property("examplesRepoUrl", examplesRepoUrl)
    inputs.property("examplesBranch", examplesBranch)
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
