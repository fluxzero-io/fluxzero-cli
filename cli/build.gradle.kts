import org.apache.tools.ant.filters.ReplaceTokens
import org.gradle.jvm.tasks.Jar

plugins {
    kotlin("jvm")
    application
    id("com.gradleup.shadow") version "9.0.0"
    id("org.graalvm.buildtools.native")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":templates"))
    implementation("com.github.ajalt.clikt:clikt:5.0.3")
    implementation("org.jline:jline:3.30.6")
}

application {
    mainClass = "host.flux.cli.MainKt"
}

graalvmNative {
    toolchainDetection.set(true)
    binaries {
        named("main") {
            imageName.set("flux")
            mainClass.set("host.flux.cli.MainKt")
            buildArgs.addAll(
                "--no-fallback",
                "--install-exit-handlers",
                "--enable-url-protocols=https",
                "--report-unsupported-elements-at-runtime",
                "--initialize-at-build-time=kotlin",
                "--initialize-at-run-time=org.jline",
                "-H:+AddAllCharsets"
            )
        }
    }
    binaries.all {
        resources.autodetect()
    }
}

tasks.shadowJar {
    mergeServiceFiles()
    archiveClassifier.set("")
    archiveBaseName.set("fluxzero-cli")
}

tasks.withType<Jar>().configureEach {
    archiveBaseName.set("fluxzero-cli")
    manifest {
        attributes(
            "Implementation-Version" to project.version,
        )
    }
}

// Fix dependency ordering issues
tasks.named("distZip") {
    dependsOn("shadowJar")
}

tasks.named("distTar") {
    dependsOn("shadowJar")
}

tasks.named("startScripts") {
    dependsOn("shadowJar")
}

tasks.named("startShadowScripts") {
    dependsOn("jar")
}

tasks.register<Copy>("generateScripts") {
    val scriptsOutputDir = layout.buildDirectory.dir("release-scripts")
    from("../scripts") {
        include("install.sh.template", "install.ps1.template", "uninstall.sh.template", "uninstall.ps1.template")
        filter<ReplaceTokens>(
            "tokens" to mapOf(
                "VERSION" to version,
            )
        )
    }
    rename { name ->
        when (name) {
            "install.sh.template" -> "install.sh"
            "install.ps1.template" -> "install.ps1"
            "uninstall.sh.template" -> "uninstall.sh"
            "uninstall.ps1.template" -> "uninstall.ps1"
            else -> name
        }
    }
    into(scriptsOutputDir)

    doLast {
        file(layout.buildDirectory.file("release-scripts/install.sh")).setExecutable(true)
        file(layout.buildDirectory.file("release-scripts/uninstall.sh")).setExecutable(true)
        // Note: PowerShell scripts don't need executable permissions on Unix systems
    }
}