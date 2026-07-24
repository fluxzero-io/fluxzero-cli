import org.apache.tools.ant.DirectoryScanner

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

// Gradle inherits Ant's default exclusion of .gitignore files. These are project
// content in the embedded starters, while .git directories remain explicitly excluded.
DirectoryScanner.removeDefaultExclude("**/.gitignore")

rootProject.name = "fluxzero-cli"

include("templates")
include("cli")
include("api")
include("project-files")
include("dev-launcher")
include("publishing")
include("gradle-plugin")
include("maven-plugin")
