#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "Usage: $0 <cli-jar> <local-plugin-repository>" >&2
  exit 2
fi

absolute_path() {
  local path=$1
  local directory
  directory=$(cd "$(dirname "$path")" && pwd)
  printf '%s/%s\n' "$directory" "$(basename "$path")"
}

CLI_JAR=$(absolute_path "$1")
PLUGIN_REPOSITORY=$(absolute_path "$2")
[[ -f "$CLI_JAR" ]] || { echo "CLI JAR not found: $CLI_JAR" >&2; exit 1; }
[[ -d "$PLUGIN_REPOSITORY" ]] || { echo "Plugin repository not found: $PLUGIN_REPOSITORY" >&2; exit 1; }
if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]]; then
  JAVA_EXECUTABLE="$JAVA_HOME/bin/java"
else
  JAVA_EXECUTABLE=$(command -v java) || { echo "Java executable not found" >&2; exit 1; }
fi

WORKSPACE=$(mktemp -d "${TMPDIR:-/tmp}/fluxzero-embedded-templates.XXXXXX")
trap 'rm -rf "$WORKSPACE"' EXIT

require_text() {
  local file=$1
  local text=$2
  grep -Fq -- "$text" "$file" || {
    echo "Expected '$text' in $file" >&2
    exit 1
  }
}

generate_and_build() {
  local template=$1
  local build_system=$2
  local project_name=$3
  local package_name=$4
  local artifact_id="${project_name}-artifact"
  local group_id="com.acme.generated"
  local description="Customer input verification for $template with $build_system"
  local package_path=${package_name//./\/}

  echo "Generating $template with $build_system as $project_name"
  (
    cd "$WORKSPACE"
    "$JAVA_EXECUTABLE" -jar "$CLI_JAR" init \
      --template="$template" \
      --name="$project_name" \
      --package="$package_name" \
      --group-id="$group_id" \
      --artifact-id="$artifact_id" \
      --description="$description" \
      --build="$build_system" \
      --git
  )

  local project_dir="$WORKSPACE/$project_name"
  local workflow="$project_dir/.github/workflows/deploy-to-fluxzero-cloud.yml"
  local source_language
  local source_extension
  if [[ "$template" == "flux-basic-java" ]]; then
    source_language="java"
    source_extension="java"
  else
    source_language="kotlin"
    source_extension="kt"
  fi
  local app_source="$project_dir/src/main/$source_language/$package_path/App.$source_extension"
  local user_provider="$project_dir/src/main/resources/META-INF/services/io.fluxzero.sdk.tracking.handling.authentication.UserProvider"

  [[ -d "$project_dir/.git" ]] || { echo "Generated Git repository not found" >&2; exit 1; }
  [[ -f "$project_dir/.gitignore" ]] || { echo "Generated .gitignore not found" >&2; exit 1; }
  [[ -f "$app_source" ]] || { echo "Generated application source not found: $app_source" >&2; exit 1; }
  require_text "$app_source" "package $package_name"
  require_text "$user_provider" "$package_name.authentication.SenderProvider"
  [[ -f "$workflow" ]] || { echo "Generated workflow not found: $workflow" >&2; exit 1; }
  [[ ! -e "$workflow.maven" ]] || { echo "Maven workflow source was not renamed" >&2; exit 1; }
  [[ ! -e "$workflow.gradle" ]] || { echo "Gradle workflow source was not renamed" >&2; exit 1; }
  require_text "$workflow" "FLUXZERO_PACKAGE_NAME: $artifact_id"
  require_text "$workflow" "uses: fluxzero-io/fluxzero-jwt-action@v2"
  require_text "$workflow" "mode: oidc"

  if [[ "$build_system" == "maven" ]]; then
    [[ -f "$project_dir/pom.xml" ]] || { echo "Generated Maven pom.xml not found" >&2; exit 1; }
    [[ ! -e "$project_dir/build.gradle.kts" ]] || { echo "Gradle build file retained in Maven project" >&2; exit 1; }
    [[ -x "$project_dir/mvnw" ]] || { echo "Maven wrapper is not executable" >&2; exit 1; }
    [[ ! -e "$project_dir/gradlew" ]] || { echo "Gradle wrapper retained in Maven project" >&2; exit 1; }
    require_text "$project_dir/pom.xml" "<groupId>$group_id</groupId>"
    require_text "$project_dir/pom.xml" "<artifactId>$artifact_id</artifactId>"
    require_text "$project_dir/pom.xml" "<name>$project_name</name>"
    require_text "$project_dir/pom.xml" "<description>$description</description>"
    require_text "$project_dir/pom.xml" "<packageName>$artifact_id</packageName>"
    require_text "$workflow" "cache: maven"
    require_text "$workflow" "./mvnw -B -ntp -T1C package"
    require_text "$workflow" "fluxzero:publish-package"
    (
      cd "$project_dir"
      export MAVEN_OPTS="${MAVEN_OPTS:-} -Dmaven.repo.local=$PLUGIN_REPOSITORY"
      ./mvnw -B -ntp package
      ./mvnw -B -ntp -Dfluxzero.package.skip=true fluxzero:publish-package
    )
  else
    [[ -f "$project_dir/build.gradle.kts" ]] || { echo "Generated Gradle build file not found" >&2; exit 1; }
    [[ -f "$project_dir/settings.gradle.kts" ]] || { echo "Generated Gradle settings not found" >&2; exit 1; }
    [[ ! -e "$project_dir/pom.xml" ]] || { echo "Maven pom.xml retained in Gradle project" >&2; exit 1; }
    [[ -x "$project_dir/gradlew" ]] || { echo "Gradle wrapper is not executable" >&2; exit 1; }
    [[ ! -e "$project_dir/mvnw" ]] || { echo "Maven wrapper retained in Gradle project" >&2; exit 1; }
    require_text "$project_dir/build.gradle.kts" "group = \"$group_id\""
    require_text "$project_dir/build.gradle.kts" "packageName.set(\"$artifact_id\")"
    require_text "$project_dir/settings.gradle.kts" "rootProject.name = \"$artifact_id\""
    require_text "$workflow" "cache: gradle"
    require_text "$workflow" "./gradlew --no-daemon build"
    require_text "$workflow" "fluxzeroPublishPackage"
    (
      cd "$project_dir"
      export GRADLE_OPTS="${GRADLE_OPTS:-} -Dmaven.repo.local=$PLUGIN_REPOSITORY"
      ./gradlew --no-daemon --console=plain build
      ./gradlew --no-daemon --console=plain fluxzeroPublishPackage --dry-run
    )
  fi
}

generate_and_build flux-basic-java maven embedded-java-maven com.acme.embedded.javamaven
generate_and_build flux-basic-java gradle embedded-java-gradle com.acme.embedded.javagradle
generate_and_build flux-basic-kotlin maven embedded-kotlin-maven com.acme.embedded.kotlinmaven
generate_and_build flux-basic-kotlin gradle embedded-kotlin-gradle com.acme.embedded.kotlingradle

echo "All embedded template variants built successfully"
