import com.vanniktech.maven.publish.GradlePlugin
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.SonatypeHost

plugins {
    kotlin("jvm")
    `java-gradle-plugin`
    id("com.vanniktech.maven.publish")
    id("com.gradleup.shadow") version "9.3.1"
}

// Configuration for dependencies that should be embedded into the shadow JAR
val shade by configurations.creating {
    isTransitive = true
}

dependencies {
    shade(project(":project-files"))
    compileOnly(project(":project-files"))

    implementation(gradleApi())
    implementation(gradleKotlinDsl())

    // Testing
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.14.3")
    testImplementation("io.mockk:mockk:1.14.9")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // Functional testing
    testImplementation(gradleTestKit())
    testImplementation(project(":project-files"))
}

group = "io.fluxzero.tools"

// Shadow JAR: embed project-files and its dependencies into the plugin JAR
tasks.shadowJar {
    archiveClassifier.set("")
    configurations = listOf(shade)
    relocate("kotlinx.serialization", "host.flux.gradle.shadow.kotlinx.serialization")
    relocate("io.github.microutils", "host.flux.gradle.shadow.io.github.microutils")
}

tasks.jar {
    archiveClassifier.set("plain")
}

// Ensure functional tests use the shadow JAR (which embeds project-files)
tasks.named<PluginUnderTestMetadata>("pluginUnderTestMetadata") {
    pluginClasspath.from(shade)
}

gradlePlugin {
    plugins {
        create("fluxzero") {
            id = "io.fluxzero.tools.gradle.plugin"
            implementationClass = "host.flux.gradle.FluxzeroPlugin"
            displayName = "Fluxzero Gradle Plugin"
            description = "Gradle plugin for Fluxzero projects - syncs project files and more"
        }
    }
}

mavenPublishing {
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)

    // Only sign if GPG key is available (CI has it, local dev may not)
    if (project.findProperty("signingInMemoryKey") != null) {
        signAllPublications()
    }

    // Publish as Gradle plugin using shadow JAR (embeds project-files and its dependencies)
    configure(GradlePlugin(JavadocJar.Empty(), sourcesJar = true))

    coordinates("io.fluxzero.tools", "fluxzero-gradle-plugin", version.toString())

    pom {
        name.set("Fluxzero Gradle Plugin")
        description.set("Gradle plugin for Fluxzero projects - syncs project files and more")
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

// Ensure all outgoing variants use the shadow JAR instead of the plain JAR
listOf(configurations.apiElements, configurations.runtimeElements).forEach {
    it.configure {
        outgoing.artifacts.clear()
        outgoing.artifact(tasks.shadowJar)
    }
}

// Functional test configuration
val functionalTest by sourceSets.creating

configurations[functionalTest.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[functionalTest.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())

val functionalTestTask = tasks.register<Test>("functionalTest") {
    description = "Runs the functional tests."
    group = "verification"
    testClassesDirs = functionalTest.output.classesDirs
    classpath = functionalTest.runtimeClasspath
    useJUnitPlatform()
}

tasks.check {
    dependsOn(functionalTestTask)
}

tasks.test {
    useJUnitPlatform()
}
