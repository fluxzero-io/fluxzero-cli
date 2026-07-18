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

fun ZipFile.readRequiredInstruction(templateName: String, entryName: String): String {
    val entry = getEntry(entryName)
    require(entry != null) {
        "$templateName.zip is missing the required $entryName instruction."
    }
    return getInputStream(entry).bufferedReader().use { it.readText() }
}

fun ZipFile.verifyAgentInstructions(templateName: String) {
    val agentsText = readRequiredInstruction(templateName, "AGENTS.md")
    val requiredAgentGuidance = listOf(
        "installed Fluxzero integration",
        "Fluxzero MCP server",
        "build-fluxzero-app",
        "fluxzero-io/fluxzero-agent-integrations",
        "repository-local Fluxzero manuals"
    )
    require(requiredAgentGuidance.all(agentsText::contains)) {
        "$templateName.zip AGENTS.md must install or use the Fluxzero integration, route SDK guidance through MCP, and reject local manuals."
    }

    val claudeText = readRequiredInstruction(templateName, "CLAUDE.md")
    require(
        "@AGENTS.md" in claudeText &&
            "claude plugin marketplace add fluxzero-io/fluxzero-agent-integrations" in claudeText &&
            "claude plugin install fluxzero@fluxzero" in claudeText
    ) {
        "$templateName.zip CLAUDE.md must import the shared instructions and bootstrap the Fluxzero Claude plugin."
    }

    val geminiText = readRequiredInstruction(templateName, "GEMINI.md")
    require(
        "@./AGENTS.md" in geminiText &&
            "gemini extensions install https://github.com/fluxzero-io/fluxzero-agent-integrations" in geminiText
    ) {
        "$templateName.zip GEMINI.md must import the shared instructions and bootstrap the Fluxzero Gemini extension."
    }

    val forbiddenEntries = entries().asSequence()
        .filterNot { it.isDirectory }
        .map { it.name }
        .filter { name ->
            name.endsWith("/AGENTS.md") ||
                name.endsWith("/CLAUDE.md") ||
                name.endsWith("/GEMINI.md") ||
                name.startsWith(".fluxzero/agents/") || name.contains("/.fluxzero/agents/")
        }
        .toList()
    require(forbiddenEntries.isEmpty()) {
        "$templateName.zip contains nested duplicate or retired agent instructions: ${forbiddenEntries.joinToString()}"
    }
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

val verifyPackagedTemplates by tasks.registering {
    group = "verification"
    description = "Verify packaged templates include current login support and minimal plugin guidance."

    dependsOn(packageTemplates)

    val javaTemplateZip = generatedTemplatesDir.map { it.file("flux-basic-java.zip") }
    val kotlinTemplateZip = generatedTemplatesDir.map { it.file("flux-basic-kotlin.zip") }
    inputs.files(javaTemplateZip, kotlinTemplateZip)

    doLast {
        val zipFile = javaTemplateZip.get().asFile
        require(zipFile.isFile) { "Missing packaged template: ${zipFile.absolutePath}" }

        ZipFile(zipFile).use { zip ->
            zip.verifyAgentInstructions("flux-basic-java")

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

        val kotlinZipFile = kotlinTemplateZip.get().asFile
        require(kotlinZipFile.isFile) { "Missing packaged template: ${kotlinZipFile.absolutePath}" }
        ZipFile(kotlinZipFile).use { zip ->
            zip.verifyAgentInstructions("flux-basic-kotlin")
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
