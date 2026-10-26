plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
    id("com.gradleup.shadow") version "9.6.1"
    id("org.graalvm.buildtools.native")
}

dependencies {
    implementation(project(":templates"))
    
    // Ktor server dependencies
    implementation("io.ktor:ktor-server-core:3.6.0")
    implementation("io.ktor:ktor-server-cio:3.6.0")
    implementation("io.ktor:ktor-server-content-negotiation:3.6.0")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.6.0")
    implementation("io.ktor:ktor-server-cors:3.6.0")
    implementation("io.ktor:ktor-server-status-pages:3.6.0")
    
    // Logging implementation
    implementation("ch.qos.logback:logback-classic:1.6.6")
    implementation("org.apache.commons:commons-compress:1.28.0")
    
    // Testing dependencies
    testImplementation("io.ktor:ktor-server-test-host:3.6.0")
    testImplementation("io.ktor:ktor-client-content-negotiation:3.6.0")
}

application {
    mainClass = "host.flux.api.ApplicationKt"
}

tasks.shadowJar {
    // Keep all service and Kotlin metadata inputs available to their transformers.
    filesMatching(listOf("META-INF/services/**", "META-INF/*.kotlin_module")) {
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
    }
    failOnDuplicateEntries = true
    mergeServiceFiles()
    archiveClassifier.set("")
    archiveBaseName.set("flux-api")
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

graalvmNative {
    toolchainDetection.set(true)
    binaries {
        named("main") {
            javaLauncher.set(javaToolchains.launcherFor {
                languageVersion.set(JavaLanguageVersion.of(25))
                vendor.set(JvmVendorSpec.GRAAL_VM)
            })
            imageName.set("flux-api")
            mainClass.set("host.flux.api.ApplicationKt")
            buildArgs.addAll(
                "--no-fallback",
                "--install-exit-handlers",
                "--enable-url-protocols=https",
                "--report-unsupported-elements-at-runtime",
                "--initialize-at-build-time=kotlin",
                "-H:+AddAllCharsets"
            )
        }
    }
    binaries.all {
        resources.autodetect()
    }
}
