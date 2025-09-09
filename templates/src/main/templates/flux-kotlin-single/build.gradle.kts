plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.detekt)
}

group = "com.example.flux"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    // Spring
    implementation(libs.spring.boot.starter)

    // Flux
    implementation(libs.flux.capacitor.client)
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")

    // Monitoring
    implementation(libs.kotlin.logging)

    // Testing
    testImplementation(kotlin("test"))
    testImplementation(libs.flux.capacitor.client) {
        artifact {
            classifier = "tests"
        }
    }

    // For running the test servers
    testImplementation(libs.flux.capacitor.test.server)
    testImplementation(libs.flux.capacitor.proxy)
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(
        libs.versions.jdk
            .get()
            .toInt(),
    )
}

