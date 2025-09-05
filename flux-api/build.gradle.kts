plugins {
    kotlin("jvm")
    kotlin("plugin.serialization") version "2.1.20"
    application
    id("com.gradleup.shadow") version "8.3.6"
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":flux-templates"))
    
    // Ktor server dependencies
    implementation("io.ktor:ktor-server-core:2.3.12")
    implementation("io.ktor:ktor-server-netty:2.3.12")
    implementation("io.ktor:ktor-server-content-negotiation:2.3.12")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.12")
    implementation("io.ktor:ktor-server-cors:2.3.12")
    implementation("io.ktor:ktor-server-status-pages:2.3.12")
    
    // Testing dependencies
    testImplementation("io.ktor:ktor-server-test-host:2.3.12")
    testImplementation("io.ktor:ktor-client-content-negotiation:2.3.12")
}

application {
    mainClass = "host.flux.api.ApplicationKt"
}

tasks.shadowJar {
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