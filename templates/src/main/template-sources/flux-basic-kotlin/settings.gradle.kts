pluginManagement {
    repositories {
        mavenLocal()
        maven { url = uri("https://packages.fluxzero.io/maven") }
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}
rootProject.name = "flux-basic-kotlin"
