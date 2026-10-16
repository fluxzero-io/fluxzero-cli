pluginManagement {
    repositories {
        mavenLocal()
        maven { url = uri("https://packages.fluxzero.io/maven") }
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
rootProject.name = "flux-basic-kotlin"
