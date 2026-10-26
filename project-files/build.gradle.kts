plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

group = "io.fluxzero.tools"

dependencies {
    // JSON serialization (for GitHub API responses)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")

    // Logging
    implementation("io.github.oshai:kotlin-logging-jvm:8.0.4")
    implementation("org.slf4j:slf4j-api:2.0.20")

    // Testing
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
    testImplementation("io.mockk:mockk:1.14.11")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("ch.qos.logback:logback-classic:1.6.6")
}

tasks.test {
    useJUnitPlatform()
}
