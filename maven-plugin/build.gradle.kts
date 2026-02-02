plugins {
    kotlin("jvm")
    `maven-publish`
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

// Custom JAR task that includes the plugin descriptor
tasks.jar {
    dependsOn(generatePluginDescriptor)

    // Include the generated plugin.xml (only the maven directory)
    from(file("target/classes/META-INF/maven")) {
        into("META-INF/maven")
    }

    archiveBaseName.set("fluxzero-maven-plugin")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "fluxzero-maven-plugin"
            from(components["java"])

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
