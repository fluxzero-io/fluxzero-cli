plugins {
    kotlin("jvm")
    id("org.gradlex.maven-plugin-development") version "1.0.3"
    `maven-publish`
}

group = "io.fluxzero.tools"

dependencies {
    // Reuse shared core library
    implementation(project(":agents-core"))

    // Maven Plugin API
    compileOnly("org.apache.maven:maven-plugin-api:3.9.6")
    compileOnly("org.apache.maven:maven-core:3.9.6")
    compileOnly("org.apache.maven.plugin-tools:maven-plugin-annotations:3.13.1")

    // Testing
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
}

mavenPlugin {
    goalPrefix.set("fluxzero")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "fluxzero-maven-plugin"
            from(components["java"])
        }
    }
}
