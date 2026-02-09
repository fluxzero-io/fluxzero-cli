import com.vanniktech.maven.publish.SonatypeHost

plugins {
    kotlin("jvm")
    `java-gradle-plugin`
    id("com.vanniktech.maven.publish")
    id("com.gradle.plugin-publish")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":project-files"))
    implementation(gradleApi())
    implementation(gradleKotlinDsl())

    // Testing
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
    testImplementation("io.mockk:mockk:1.14.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // Functional testing
    testImplementation(gradleTestKit())
}

group = "io.fluxzero.tools"

gradlePlugin {
    website.set("https://fluxzero.io")
    vcsUrl.set("https://github.com/fluxzero-io/fluxzero-cli")

    plugins {
        create("fluxzero") {
            id = "io.fluxzero.tools.gradle"
            implementationClass = "host.flux.gradle.FluxzeroPlugin"
            displayName = "Fluxzero Gradle Plugin"
            description = "Gradle plugin for Fluxzero projects - syncs project files and more"
            tags.set(listOf("fluxzero", "ai", "agents", "code-generation"))
        }
    }
}

mavenPublishing {
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)
    signAllPublications()

    coordinates("io.fluxzero.tools", "fluxzero-gradle-plugin", version.toString())

    pom {
        name.set("Fluxzero Gradle Plugin")
        description.set("Gradle plugin for Fluxzero projects - syncs project files and more")
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

// Functional test configuration
val functionalTest by sourceSets.creating

configurations[functionalTest.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[functionalTest.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())

val functionalTestTask = tasks.register<Test>("functionalTest") {
    description = "Runs the functional tests."
    group = "verification"
    testClassesDirs = functionalTest.output.classesDirs
    classpath = functionalTest.runtimeClasspath
    useJUnitPlatform()
}

tasks.check {
    dependsOn(functionalTestTask)
}

tasks.test {
    useJUnitPlatform()
}
