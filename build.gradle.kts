plugins {
    kotlin("jvm") version "2.1.20" apply false
    kotlin("plugin.serialization") version "2.1.20" apply false
    id("org.graalvm.buildtools.native") version "0.10.6" apply false
    id("com.vanniktech.maven.publish") version "0.30.0" apply false
}

group = "host.flux"
version = (project.properties["appVersion"] ?: "dev") as String

allprojects {
    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "kotlin")

    group = rootProject.group
    version = rootProject.version

    configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
        jvmToolchain(21)
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }

    dependencies {
        "testImplementation"(kotlin("test"))
        "testImplementation"("io.mockk:mockk:1.14.2")
    }
}