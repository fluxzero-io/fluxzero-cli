import com.vanniktech.maven.publish.JavaLibrary
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.SonatypeHost
import org.w3c.dom.Document
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

plugins {
    kotlin("jvm")
    id("com.vanniktech.maven.publish")
    id("com.gradleup.shadow") version "9.0.0"
}

group = "io.fluxzero.tools"

val mavenPluginMojoDescriptions = mapOf(
    "sync-project-files" to "Synchronizes Fluxzero AI agent instruction files for a Maven project by detecting the SDK version and language, " +
        "downloading matching project files, and writing them to the project root."
)

val mavenPluginParameterDescriptions = mapOf(
    "enabled" to "Controls whether the sync-project-files goal runs. Set this to false to disable Fluxzero project-file sync without removing the plugin. " +
        "Command-line property: fluxzero.projectFiles.enabled.",
    "forceUpdate" to "When true, re-downloads and rewrites project files even when the local sync metadata says they are already current. " +
        "Command-line property: fluxzero.projectFiles.forceUpdate.",
    "overrideLanguage" to "Overrides automatic language detection. Accepted values are kotlin and java. Leave unset to detect the language from the Maven project. " +
        "Command-line property: fluxzero.projectFiles.overrideLanguage.",
    "overrideSdkVersion" to "Overrides automatic Fluxzero SDK version detection. Use this when the SDK version cannot be inferred from dependencies, BOMs, or properties. " +
        "Command-line property: fluxzero.projectFiles.overrideSdkVersion.",
    "projectDir" to "Maven-provided project base directory where Fluxzero project files are synced. This value is read-only and normally should not be configured.",
    "rootProjectOnly" to "Controls multi-module execution. When true, sync only runs in the Maven execution root; set false to run in every module. " +
        "Command-line property: fluxzero.projectFiles.rootProjectOnly.",
    "session" to "Maven-provided session used to determine the execution root in multi-module builds. This value is read-only and should not be configured.",
    "skip" to "Legacy opt-out flag. Set this to true to skip execution; prefer enabled=false for new configurations. " +
        "Command-line property: fluxzero.projectFiles.skip."
)

fun Element.firstDirectChild(tagName: String): Element? =
    (0 until childNodes.length)
        .asSequence()
        .map { childNodes.item(it) }
        .filterIsInstance<Element>()
        .firstOrNull { it.tagName == tagName }

fun Element.setDirectDescription(document: Document, description: String, insertAfterTag: String? = null) {
    val descriptionElement = firstDirectChild("description")
    if (descriptionElement != null) {
        descriptionElement.textContent = description
        return
    }

    val newDescription = document.createElement("description")
    newDescription.textContent = description
    val insertAfter = insertAfterTag?.let { firstDirectChild(it) }
    if (insertAfter?.nextSibling != null) {
        insertBefore(newDescription, insertAfter.nextSibling)
    } else {
        appendChild(newDescription)
    }
}

// Configuration for dependencies that should be embedded into the shadow JAR
val shade by configurations.creating {
    isTransitive = true
}

dependencies {
    // Reuse shared core library (shade into fat JAR, compileOnly to keep out of published POM)
    shade(project(":project-files"))
    compileOnly(project(":project-files"))

    // Maven Plugin API (compile only - provided at runtime by Maven)
    compileOnly("org.apache.maven:maven-plugin-api:3.9.6")
    compileOnly("org.apache.maven:maven-core:3.9.6")
    compileOnly("org.apache.maven.plugin-tools:maven-plugin-annotations:3.15.1")

    // Test dependencies
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.apache.maven.plugin-testing:maven-plugin-testing-harness:3.3.0")
    testImplementation("org.apache.maven:maven-core:3.9.6")
    testImplementation("org.apache.maven:maven-compat:3.9.6")
}

// Copy compiled classes to Maven's expected location (target/classes)
val copyClassesForMaven by tasks.registering(Sync::class) {
    from(tasks.compileKotlin.map { it.destinationDirectory })
    into(file("target/classes"))
    dependsOn(tasks.compileKotlin)
}

// Generate Maven plugin descriptor using Maven's plugin:descriptor goal
val generatePluginDescriptor by tasks.registering(Exec::class) {
    description = "Generates Maven plugin descriptor (plugin.xml)"
    group = "build"

    dependsOn(copyClassesForMaven)

    workingDir = projectDir

    // Pass version via -Drevision (CI-friendly versions)
    commandLine("mvn", "plugin:descriptor", "-Drevision=${project.version}", "-q")

    inputs.dir(file("target/classes"))
    inputs.property("version", project.version)
    outputs.dir(file("target/classes/META-INF/maven"))

    doLast {
        val descriptor = file("target/classes/META-INF/maven/plugin.xml")
        val document = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(descriptor)

        val mojos = document.getElementsByTagName("mojo")
        for (mojoIndex in 0 until mojos.length) {
            val mojo = mojos.item(mojoIndex) as Element
            val goal = mojo.firstDirectChild("goal")?.textContent

            mavenPluginMojoDescriptions[goal]?.let {
                mojo.setDirectDescription(document, it, insertAfterTag = "goal")
            }

            val parameters = mojo.getElementsByTagName("parameter")
            for (parameterIndex in 0 until parameters.length) {
                val parameter = parameters.item(parameterIndex) as Element
                val name = parameter.firstDirectChild("name")?.textContent
                mavenPluginParameterDescriptions[name]?.let {
                    parameter.setDirectDescription(document, it)
                }
            }
        }

        TransformerFactory.newInstance()
            .newTransformer()
            .apply {
                setOutputProperty(OutputKeys.INDENT, "yes")
                setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2")
            }
            .transform(DOMSource(document), StreamResult(descriptor))
    }
}

// Configure shadow JAR to include all dependencies
tasks.shadowJar {
    dependsOn(generatePluginDescriptor)

    // Include shade configuration so project-files gets bundled into the fat JAR
    configurations = listOf(
        project.configurations.runtimeClasspath.get(),
        shade
    )

    // Include the generated plugin.xml
    from(file("target/classes/META-INF/maven")) {
        into("META-INF/maven")
    }

    archiveBaseName.set("fluxzero-maven-plugin")
    archiveClassifier.set("") // No classifier - this is the main artifact

    // Relocate dependencies to avoid classpath conflicts with project dependencies
    relocate("kotlinx.serialization", "host.flux.maven.shadow.kotlinx.serialization")
    relocate("io.github.microutils", "host.flux.maven.shadow.io.github.microutils")
}

// Disable the regular JAR task since we're using shadow
tasks.jar {
    enabled = false
}

// Make assemble depend on shadowJar
tasks.assemble {
    dependsOn(tasks.shadowJar)
}

mavenPublishing {
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)

    // Only sign if GPG key is available (CI has it, local dev may not)
    if (project.findProperty("signingInMemoryKey") != null) {
        signAllPublications()
    }

    // Use shadow component for publishing the fat JAR
    configure(JavaLibrary(JavadocJar.Empty(), false))

    coordinates("io.fluxzero.tools", "fluxzero-maven-plugin", version.toString())

    pom {
        packaging = "maven-plugin"
        name.set("Fluxzero Maven Plugin")
        description.set("Maven plugin for Fluxzero projects - syncs project files")
        url.set("https://fluxzero.io")
        inceptionYear.set("2025")

        licenses {
            license {
                name.set("EUPL-1.2")
                url.set("https://eupl.eu/1.2/en/")
            }
        }

        developers {
            developer {
                id.set("fluxzero")
                name.set("Fluxzero Team")
                url.set("https://fluxzero.io")
            }
        }

        scm {
            url.set("https://github.com/fluxzero-io/fluxzero-cli")
            connection.set("scm:git:git://github.com/fluxzero-io/fluxzero-cli.git")
            developerConnection.set("scm:git:ssh://git@github.com/fluxzero-io/fluxzero-cli.git")
        }
    }
}

// Replace the default JAR with the shadow JAR in publications
afterEvaluate {
    publishing {
        publications.withType<MavenPublication> {
            // Replace the main artifact with shadow JAR
            artifacts.removeIf { it.classifier == null || it.classifier == "" }
            artifact(tasks.shadowJar)
        }
    }
}

tasks.named("clean") {
    doLast {
        delete("target")
    }
}

tasks.test {
    useJUnit()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
    }
}
