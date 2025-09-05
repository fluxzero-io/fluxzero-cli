plugins {
    kotlin("jvm") version "2.1.20" apply false
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

    tasks.withType<Test> {
        useJUnitPlatform()
    }

    dependencies {
        "testImplementation"(kotlin("test"))
        "testImplementation"("io.mockk:mockk:1.14.2")
    }
}