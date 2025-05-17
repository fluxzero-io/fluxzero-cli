import org.apache.tools.ant.filters.ReplaceTokens

plugins {
    application
    kotlin("jvm") version "2.1.20"
    id("com.gradleup.shadow") version "8.3.6"
}

group = "host.flux.cli"
version = (project.properties["appVersion"] ?: "1.0.0") as String

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.github.ajalt.clikt:clikt:5.0.3")
    implementation("com.github.ajalt.clikt:clikt-markdown:5.0.3")
    implementation("org.jline:jline:3.30.0")

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(21)
}

application {
    mainClass = "host.flux.cli.MainKt"
}

tasks.shadowJar {
    mergeServiceFiles()
}

tasks.register<Copy>("generateScripts") {
    val scriptsOutputDir = layout.buildDirectory.dir("release-scripts")
    from("scripts") {
        include("install.sh.template")
        filter<ReplaceTokens>(
            "tokens" to mapOf(
                "VERSION" to version,
            )
        )
    }
    rename("install.sh.template", "install.sh")
    into(scriptsOutputDir)

    doLast {
        file(layout.buildDirectory.file("release-scripts/install.sh")).setExecutable(true)
    }
}