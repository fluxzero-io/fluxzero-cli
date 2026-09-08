#!/usr/bin/env bash
set -euo pipefail
version=${1:?release version required}
repository=${2:?Maven repository URL required}
[[ "$version" =~ ^[a-zA-Z0-9.+-]+$ ]] || exit 2
repository=${repository%/}
root=$(cd "$(dirname "$0")/.." && pwd)
work=$(mktemp -d "${TMPDIR:-/tmp}/fluxzero-plugin-consumer.XXXXXX")
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/maven" "$work/gradle"
cat > "$work/maven/pom.xml" <<POM
<project><modelVersion>4.0.0</modelVersion><groupId>test</groupId><artifactId>consumer</artifactId><version>1</version>
<pluginRepositories><pluginRepository><id>qualified-repository</id><url>$repository</url></pluginRepository></pluginRepositories>
</project>
POM
# Execute a real Mojo. Disable remote instruction sync, which is unrelated to publication.
mvn -B -ntp -Dmaven.repo.local="$work/maven-cache" -f "$work/maven/pom.xml" \
  "io.fluxzero.tools:fluxzero-maven-plugin:$version:sync-project-files" \
  -Dfluxzero.projectFiles.enabled=false
for extension in pom jar; do
  provenance="$work/maven-cache/io/fluxzero/tools/fluxzero-maven-plugin/$version/_remote.repositories"
  grep -F "fluxzero-maven-plugin-$version.$extension>qualified-repository=" "$provenance"
done
cat > "$work/gradle/settings.gradle.kts" <<SETTINGS
pluginManagement {
    repositories {
        exclusiveContent {
            forRepository { maven { url = uri("$repository") } }
            filter { includeGroupByRegex("io\\\\.fluxzero.*") }
        }
        mavenCentral()
    }
}
rootProject.name = "independent-consumer"
SETTINGS
cat > "$work/gradle/build.gradle.kts" <<GRADLE
plugins {
    java
    id("io.fluxzero.tools.gradle.plugin") version "$version"
}
fluxzero { projectFiles { enabled.set(false) } }
GRADLE
# Exclusive repository routing forbids Central/Plugin Portal fallback for both marker and implementation.
"$root/gradlew" --no-daemon -g "$work/gradle-cache" -p "$work/gradle" fluxzeroDevMetadata --info
printf 'Qualified Maven Mojo and Gradle plugin marker/implementation for %s from %s\n' "$version" "$repository"
