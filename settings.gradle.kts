plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "fluxzero-cli"

include("templates")
include("cli")
include("api")
include("project-files")
include("publishing")
include("gradle-plugin")
include("maven-plugin")
