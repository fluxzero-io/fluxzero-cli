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

  echo "Generating $template with $build_system as $project_name"
  (
    cd "$WORKSPACE"
    "$JAVA_EXECUTABLE" -jar "$CLI_JAR" init \
      --template="$template" \
      --name="$project_name" \
      --package="$package_name" \
      --group-id=com.acme \
      --artifact-id="$project_name" \
      --description="Embedded template verification" \
      --build="$build_system" \
      --git
  )

  local project_dir="$WORKSPACE/$project_name"
  local workflow="$project_dir/.github/workflows/deploy-to-fluxzero-cloud.yml"
  [[ -f "$project_dir/.gitignore" ]] || { echo "Generated .gitignore not found" >&2; exit 1; }
  [[ -f "$workflow" ]] || { echo "Generated workflow not found: $workflow" >&2; exit 1; }
  [[ ! -e "$workflow.maven" ]] || { echo "Maven workflow source was not renamed" >&2; exit 1; }
  [[ ! -e "$workflow.gradle" ]] || { echo "Gradle workflow source was not renamed" >&2; exit 1; }
  require_text "$workflow" "FLUXZERO_PACKAGE_NAME: $project_name"
  require_text "$workflow" "uses: fluxzero-io/fluxzero-jwt-action@v2"
  require_text "$workflow" "mode: oidc"

  if [[ "$build_system" == "maven" ]]; then
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
