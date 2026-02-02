// Maven plugin is built using Maven (for proper Kotlin mojo support)
// but version is managed by Gradle for consistency

plugins {
    base
}

group = "io.fluxzero.tools"

// Task to build the Maven plugin using Maven
val buildMavenPlugin by tasks.registering(Exec::class) {
    description = "Builds the Maven plugin using Maven"
    group = "build"

    // Ensure agents-core is published first (Maven depends on it)
    dependsOn(":agents-core:publishToMavenLocal")

    workingDir = projectDir

    // Set version and build
    commandLine(
        "bash", "-c",
        "mvn versions:set -DnewVersion=${project.version} -DgenerateBackupPoms=false -q && mvn clean install -DskipTests"
    )

    inputs.files(fileTree("src"))
    inputs.file("pom.xml")
    inputs.property("version", project.version)
    outputs.dir("target")
}

// Task to publish to local Maven repository
val publishToMavenLocal by tasks.registering {
    description = "Publishes the Maven plugin to the local Maven repository"
    group = "publishing"
    dependsOn(buildMavenPlugin)
}

tasks.named("build") {
    dependsOn(buildMavenPlugin)
}

tasks.named("clean") {
    doLast {
        delete("target")
    }
}
