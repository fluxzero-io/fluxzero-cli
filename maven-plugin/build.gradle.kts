plugins {
    kotlin("jvm")
    `maven-publish`
    id("com.gradleup.shadow") version "8.3.6"
}

group = "io.fluxzero.tools"

dependencies {
    // Reuse shared core library
    implementation(project(":agents-core"))

    // Maven Plugin API (compile only - provided at runtime by Maven)
    compileOnly("org.apache.maven:maven-plugin-api:3.9.6")
    compileOnly("org.apache.maven:maven-core:3.9.6")
    compileOnly("org.apache.maven.plugin-tools:maven-plugin-annotations:3.15.1")
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

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "fluxzero-maven-plugin"

            // Use shadow JAR as the main artifact
            artifact(tasks.shadowJar)

            pom {
                packaging = "maven-plugin"
                name.set("Fluxzero Maven Plugin")
                description.set("Maven plugin for Fluxzero projects - syncs AI agent files")
            }
        }
    }
}

tasks.named("clean") {
    doLast {
        delete("target")
    }
}
