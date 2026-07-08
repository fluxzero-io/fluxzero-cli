plugins {
    kotlin("jvm")
}

group = "io.fluxzero.tools"

dependencies {
    implementation("com.google.cloud.tools:jib-core:0.28.1")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
}

tasks.test {
    useJUnitPlatform()
}
