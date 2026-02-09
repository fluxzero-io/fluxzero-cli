import com.vanniktech.maven.publish.SonatypeHost

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("com.vanniktech.maven.publish")
}

group = "io.fluxzero.tools"

kotlin {
    jvmToolchain(21)
}

mavenPublishing {
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)
    signAllPublications()

    coordinates("io.fluxzero.tools", "project-files", version.toString())

    pom {
        name.set("Fluxzero Project Files")
        description.set("Core library for Fluxzero project file management")
        url.set("https://fluxzero.io")
        inceptionYear.set("2025")

        licenses {
            license {
                name.set("EUPL-1.2")
                url.set("https://eupl.eu/1.2/en/")
            }
        }

        developers {
            developer {
                id.set("fluxzero")
                name.set("Fluxzero Team")
                url.set("https://fluxzero.io")
            }
        }

        scm {
            url.set("https://github.com/fluxzero-io/fluxzero-cli")
            connection.set("scm:git:git://github.com/fluxzero-io/fluxzero-cli.git")
            developerConnection.set("scm:git:ssh://git@github.com/fluxzero-io/fluxzero-cli.git")
        }
    }
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
    testRuntimeOnly("ch.qos.logback:logback-classic:1.5.6")
}

tasks.test {
    useJUnitPlatform()
}
