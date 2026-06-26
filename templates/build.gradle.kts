import org.gradle.api.tasks.PathSensitivity
import java.util.zip.ZipFile

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
// Configuration for external examples; these are forwarded to the shell script
val examplesRepoUrl: String = (findProperty("examplesRepoUrl") as String?)
    ?: System.getenv("EXAMPLES_REPO_URL")
    ?: "https://github.com/fluxzero-io/fluxzero-examples.git"
val examplesReleaseTag: String = (findProperty("examplesReleaseTag") as String?)
    ?: System.getenv("EXAMPLES_RELEASE_TAG")
    ?: "latest"
val examplesZipUrl: String? = (findProperty("examplesZipUrl") as String?)
    ?: System.getenv("EXAMPLES_ZIP_URL")
val configuredExamplesSourceDir: String? = (findProperty("examplesSourceDir") as String?)
    ?: System.getenv("EXAMPLES_SOURCE_DIR")
val defaultLocalExamplesDir = rootProject.layout.projectDirectory.dir("../fluxzero-examples").asFile
val examplesSourceDir = configuredExamplesSourceDir
    ?.let { file(it) }
    ?: defaultLocalExamplesDir.takeIf { it.isDirectory && it.resolve("flux-basic-java").isDirectory }
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
    if (examplesSourceDir != null) environment("EXAMPLES_SOURCE_DIR", examplesSourceDir.absolutePath)
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
    inputs.property("examplesSourceDir", examplesSourceDir?.absolutePath ?: "")
    if (examplesSourceDir != null) {
        inputs.files(fileTree(examplesSourceDir) {
            exclude(
                "**/.git/**",
                "**/.gradle/**",
                "**/.idea/**",
                "**/build/**",
                "**/target/**",
                "**/.DS_Store"
            )
        })
            .withPropertyName("examplesSourceFiles")
            .withPathSensitivity(PathSensitivity.RELATIVE)
    }
    inputs.property("refreshExamples", refreshExamples)
    outputs.dir(generatedTemplatesDir)
}

val verifyPackagedTemplates by tasks.registering {
    group = "verification"
    description = "Verify packaged templates include the current local login support."

    dependsOn(packageTemplates)

    val javaTemplateZip = generatedTemplatesDir.map { it.file("flux-basic-java.zip") }
    inputs.file(javaTemplateZip)

    doLast {
        val zipFile = javaTemplateZip.get().asFile
        require(zipFile.isFile) { "Missing packaged template: ${zipFile.absolutePath}" }

        ZipFile(zipFile).use { zip ->
            val authEndpoint = zip.getEntry("src/main/java/com/example/app/authentication/AppAuthEndpoint.java")
            require(authEndpoint != null) {
                "flux-basic-java.zip is missing AppAuthEndpoint.java; generated login cannot work out-of-the-box."
            }

            val staleEntries = zip.entries().asSequence()
                .filterNot { it.isDirectory }
                .filter { it.name.endsWith(".java") || it.name.endsWith(".kt") }
                .filter { entry ->
                    zip.getInputStream(entry).bufferedReader().use { it.readText() }.contains("LocalStubIdp")
                }
                .map { it.name }
                .toList()
            require(staleEntries.isEmpty()) {
                "flux-basic-java.zip still references the removed LocalStubIdp API: ${staleEntries.joinToString()}"
            }
        }
    }
}

sourceSets {
    main {
        resources {
            srcDir("build/generated/resources")
        }
    }
}

tasks.named("processResources") {
    dependsOn(verifyPackagedTemplates)
}

tasks.test {
    useJUnitPlatform()
}
