import com.vanniktech.maven.publish.JavaLibrary
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.SonatypeHost
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
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
    "sync-project-files" to "Syncs Fluxzero AI agent instruction files for a Maven project.",
    "publish-package" to "Builds and publishes a layered Java OCI package to the Fluxzero registry."
)

val mavenPluginParameterDescriptions = mapOf(
    "baseImage" to "Java runtime base image for publish-package. Property: fluxzero.package.baseImage.",
    "baseImageSource" to "Where publish-package reads the base image from: registry or docker-daemon. Property: fluxzero.package.baseImageSource.",
    "enabled" to "Enable or disable sync-project-files. Property: fluxzero.projectFiles.enabled.",
    "forceUpdate" to "Force project files to be downloaded and rewritten. Property: fluxzero.projectFiles.forceUpdate.",
    "allowDirty" to "Allow publish-package to publish from a dirty git worktree. Property: fluxzero.package.allowDirty.",
    "applicationId" to "Optional Fluxzero application id stored as OCI package metadata. Property: fluxzero.package.applicationId.",
    "packageName" to "Required public package name for publish-package. Property: fluxzero.package.name.",
    "teamId" to "Fluxzero team id for publish-package. Property: fluxzero.team.id.",
    "packageVersion" to "Package version for publish-package. Defaults to a generated git/time-based tag. Property: fluxzero.package.version.",
    "mainClass" to "Application main class for publish-package. Property: fluxzero.package.mainClass.",
    "javaToolOptions" to "Value written to JAVA_TOOL_OPTIONS for publish-package. Property: fluxzero.package.javaToolOptions.",
    "overrideLanguage" to "Override language detection with kotlin or java. Property: fluxzero.projectFiles.overrideLanguage.",
    "overrideSdkVersion" to "Override Fluxzero SDK version detection. Property: fluxzero.projectFiles.overrideSdkVersion.",
    "project" to "Read-only Maven project metadata for publish-package.",
    "projectDir" to "Read-only Maven project base directory where Fluxzero project files are synced.",
    "registryHost" to "Fluxzero registry host for publish-package. Defaults to registry.fluxzero.io. Property: fluxzero.package.registryHost.",
    "registryToken" to "Fluxzero registry token for publish-package. Property: fluxzero.package.registryToken.",
    "rootProjectOnly" to "Run only in the Maven execution root. Property: fluxzero.projectFiles.rootProjectOnly.",
    "session" to "Read-only Maven session used to determine the execution root in multi-module builds.",
    "skip" to "Legacy opt-out flag. Prefer enabled=false. Property: fluxzero.projectFiles.skip.",
    "skipPackagePublish" to "Skip publish-package execution. Property: fluxzero.package.skip."
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

fun Node.removeWhitespaceTextNodes() {
    for (index in childNodes.length - 1 downTo 0) {
        val child = childNodes.item(index)
        if (child.nodeType == Node.TEXT_NODE && child.textContent.isBlank()) {
            removeChild(child)
        } else {
            child.removeWhitespaceTextNodes()
        }
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

    shade(project(":publishing"))
    compileOnly(project(":publishing"))

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

        document.removeWhitespaceTextNodes()

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
        description.set("Maven plugin for Fluxzero projects - syncs project files and publishes layered Java packages")
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
