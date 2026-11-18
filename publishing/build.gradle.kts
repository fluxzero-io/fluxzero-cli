plugins {
    kotlin("jvm")
}

group = "io.fluxzero.tools"

dependencies {
    implementation("com.google.cloud.tools:jib-core:0.28.2")
    implementation(platform("com.fasterxml.jackson:jackson-bom:2.23.0")) {
        because("Jib 0.28.2 still selects vulnerable Jackson 2.21.1; revisit with Jib's next release")
    }

    constraints {
        implementation("com.google.guava:guava:33.7.2-jre") {
            because("Jib 0.28.2 resolves Guava 32.1.2 with vulnerable collection deserialization")
        }
        implementation("org.apache.commons:commons-compress:1.28.0") {
            because("Jib 0.28.2 still selects vulnerable commons-lang3 through Compress 1.26; revisit with Jib's next release")
        }
    }

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
}

tasks.test {
    useJUnitPlatform()
}
