plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

group = "io.fluxzero.tools"

kotlin {
    jvmToolchain(21)
}

dependencies {
    // JSON serialization (for GitHub API responses)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Logging
    implementation("io.github.microutils:kotlin-logging-jvm:3.0.5")
    implementation("org.slf4j:slf4j-api:2.0.16")

    // Testing
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
    testImplementation("io.mockk:mockk:1.14.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("ch.qos.logback:logback-classic:1.5.29")
}

tasks.test {
    useJUnitPlatform()
}
