plugins {
    `java-library`
    `maven-publish`
}

group = "io.fluxzero.tools"

dependencies {
    implementation("com.fasterxml.jackson.core:jackson-databind:2.23.0")
    implementation("net.java.dev.jna:jna:5.19.1")
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    withSourcesJar()
    withJavadocJar()
}

publishing {
    publications {
        create<MavenPublication>("localAccount") {
            from(components["java"])
            artifactId = "fluxzero-local-account"
        }
    }
}
