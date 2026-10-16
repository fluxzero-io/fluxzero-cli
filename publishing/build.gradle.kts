plugins {
    kotlin("jvm")
}

group = "io.fluxzero.tools"

dependencies {
    implementation("com.google.cloud.tools:jib-core:0.28.2")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
}

tasks.test {
    useJUnitPlatform()
}
