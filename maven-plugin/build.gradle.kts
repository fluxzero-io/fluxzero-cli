import com.vanniktech.maven.publish.SonatypeHost

plugins {
    kotlin("jvm")
    id("com.vanniktech.maven.publish")
    id("com.gradleup.shadow") version "8.3.6"
}

group = "io.fluxzero.tools"

dependencies {
    // Reuse shared core library
    implementation(project(":project-files"))

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
}

// Configure shadow JAR to include all dependencies
tasks.shadowJar {
    dependsOn(generatePluginDescriptor)

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

// Configure publishing to use shadow JAR instead of regular JAR
afterEvaluate {
    publishing {
        publications.withType<MavenPublication> {
            // Remove the default JAR artifact and add shadow JAR
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
