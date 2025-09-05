plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "fluxzero"

include("flux-templates")
include("flux-cli") 
include("flux-api")