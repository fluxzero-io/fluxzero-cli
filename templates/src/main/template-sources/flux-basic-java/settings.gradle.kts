rootProject.name = "flux-basic-java"
pluginManagement {
    repositories {
        mavenLocal()  // Must be first to find local version
        maven { url = uri("https://packages.fluxzero.io/maven") }
        mavenCentral()
        gradlePluginPortal()
    }
}