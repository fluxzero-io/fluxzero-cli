plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "fluxzero-cli"

include("templates")
include("cli")
include("api")
include("agents-core")
include("gradle-plugin")