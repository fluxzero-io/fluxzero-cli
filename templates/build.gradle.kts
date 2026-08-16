import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.bundling.Zip
import java.util.Properties

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

val templateNames = listOf("flux-basic-java", "flux-basic-kotlin")
val templateVersionsFile = layout.projectDirectory.file("template-versions.properties")
val templateVersions = Properties().apply {
    templateVersionsFile.asFile.inputStream().use(::load)
}
fun requiredTemplateVersion(name: String): String =
    templateVersions.getProperty(name)?.takeIf(String::isNotBlank)
        ?: error("Missing $name in ${templateVersionsFile.asFile}")

val templateSdkVersion = providers.gradleProperty("templateSdkVersion")
    .orElse(requiredTemplateVersion("fluxzeroSdkVersion"))
val templateIdpVersion = providers.gradleProperty("templateIdpVersion")
    .orElse(requiredTemplateVersion("fluxzeroIdpVersion"))
val latestReleasedPluginVersion = providers.gradleProperty("latestReleasedPluginVersion")
    .orElse(requiredTemplateVersion("fluxzeroPluginVersion"))
val templatePluginVersion = providers.gradleProperty("templatePluginVersion").orElse(
    providers.provider {
        project.version.toString().takeUnless { it == "dev" } ?: latestReleasedPluginVersion.get()
    }
)
val templateVersionTokens = mapOf(
    "@fluxzeroSdkVersion@" to templateSdkVersion,
    "@fluxzeroIdpVersion@" to templateIdpVersion,
    "@fluxzeroPluginVersion@" to templatePluginVersion
)
val templatesSourceDir = layout.projectDirectory.dir("src/main/template-sources")
val preparedTemplatesDir = layout.buildDirectory.dir("prepared-template-sources")
val generatedTemplatesDir = layout.buildDirectory.dir("generated/resources/templates")

val prepareTemplates by tasks.registering(Sync::class) {
    from(templatesSourceDir) {
        exclude(
            "**/.git/**",
            "**/.gradle/**",
            "**/.idea/**",
            "**/build/**",
            "**/target/**",
            "**/.DS_Store"
        )
        filesMatching(listOf("**/pom.xml", "**/build.gradle.kts")) {
            filter { line ->
                templateVersionTokens.entries.fold(line) { result, (token, version) ->
                    result.replace(token, version.get())
                }
            }
        }
    }
    into(preparedTemplatesDir)
    inputs.file(templateVersionsFile)
    inputs.property("templateSdkVersion", templateSdkVersion)
    inputs.property("templateIdpVersion", templateIdpVersion)
    inputs.property("templatePluginVersion", templatePluginVersion)
}

val packagedTemplates = templateNames.map { templateName ->
    val taskSuffix = templateName.split("-").joinToString("") { segment ->
        segment.replaceFirstChar { it.uppercaseChar() }
    }
    tasks.register<Zip>("package$taskSuffix") {
        dependsOn(prepareTemplates)
        from(preparedTemplatesDir.map { it.dir(templateName) })
        archiveFileName.set("$templateName.zip")
        destinationDirectory.set(generatedTemplatesDir)
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }
}

val writeTemplateIndex by tasks.registering {
    val indexFile = generatedTemplatesDir.map { it.file("templates.csv") }
    outputs.file(indexFile)
    doLast {
        indexFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText(templateNames.joinToString("\n", postfix = "\n"))
        }
    }
}

val packageTemplates by tasks.registering {
    group = "build"
    description = "Package the embedded project templates and inject their Fluxzero dependency versions"
    dependsOn(packagedTemplates, writeTemplateIndex)
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
    systemProperty("templateSdkVersion", templateSdkVersion.get())
    systemProperty("templateIdpVersion", templateIdpVersion.get())
    systemProperty("templatePluginVersion", templatePluginVersion.get())
}
