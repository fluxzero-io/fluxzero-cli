plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}

rootProject.name = "fluxzero-cli"

include("templates")
include("cli")
include("api")
include("project-files")
include("gradle-plugin")
include("maven-plugin")