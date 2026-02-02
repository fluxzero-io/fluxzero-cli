plugins {
    kotlin("jvm")
    `java-gradle-plugin`
    id("com.gradle.plugin-publish")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":agents-core"))
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
    vcsUrl.set("https://github.com/flux-capacitor/fluxzero-cli")

    plugins {
        create("fluxzero") {
            id = "io.fluxzero.tools.gradle"
            implementationClass = "host.flux.gradle.FluxzeroPlugin"
            displayName = "Fluxzero Gradle Plugin"
            description = "Gradle plugin for Fluxzero projects - syncs AI agent files and more"
            tags.set(listOf("fluxzero", "ai", "agents", "code-generation"))
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
