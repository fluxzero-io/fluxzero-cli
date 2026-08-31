import org.apache.tools.ant.filters.ReplaceTokens
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.tasks.Jar
import java.util.jar.Attributes
import java.util.jar.JarFile

plugins {
    kotlin("jvm")
    application
    id("com.gradleup.shadow") version "9.0.0"
    id("org.graalvm.buildtools.native")
}

dependencies {
    implementation(project(":templates"))
    implementation(project(":publishing"))
    implementation(project(":dev-launcher"))
    implementation("com.github.ajalt.clikt:clikt:5.0.3")
    implementation("org.jline:jline:3.30.4")
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
                "--enable-native-access=ALL-UNNAMED",
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
    manifest {
        attributes("Main-Class" to application.mainClass.get())
    }
}

tasks.jar {
    archiveClassifier.set("plain")
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

val verifyRunnableJar by tasks.registering {
    dependsOn(tasks.shadowJar)
    val runnableJar = tasks.shadowJar.flatMap { it.archiveFile }
    inputs.file(runnableJar)
    doLast {
        JarFile(runnableJar.get().asFile).use { jar ->
            check(jar.manifest.mainAttributes.getValue(Attributes.Name.MAIN_CLASS) == application.mainClass.get()) {
                "Runnable CLI jar is missing Main-Class ${application.mainClass.get()}"
            }
        }
        check(runnableJar.get().asFile != tasks.jar.get().archiveFile.get().asFile) {
            "Plain and runnable CLI jars must not use the same output path"
        }
    }
}

tasks.check {
    dependsOn(verifyRunnableJar)
}

val greenfieldMcpE2e by tasks.registering(Test::class) {
    description = "Verifies greenfield MCP bootstrap with the release candidate CLI"
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform {
        includeTags("greenfield-mcp-release-e2e", "greenfield-mcp-concurrency-e2e")
    }
    outputs.upToDateWhen { false }
    shouldRunAfter(tasks.test)
}

val greenfieldMcpConcurrencyE2e by tasks.registering(Test::class) {
    description = "Verifies concurrent greenfield MCP bootstrap with a native CLI"
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform {
        includeTags("greenfield-mcp-concurrency-e2e")
    }
    outputs.upToDateWhen { false }
    shouldRunAfter(tasks.test)
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
