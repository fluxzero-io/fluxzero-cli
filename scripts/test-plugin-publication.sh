#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
work=$(mktemp -d "/tmp/fz-publish.XXXXXX")
trap 'rm -rf "$work"' EXIT
mkdir -m 700 "$work/gpg"
# An ephemeral test key exercises exactly the same signing configuration as releases.
gpg --homedir "$work/gpg" --batch --pinentry-mode loopback --passphrase '' \
  --quick-generate-key 'Publication Test <publication@example.invalid>' rsa2048 sign 0
export ORG_GRADLE_PROJECT_signingInMemoryKey
ORG_GRADLE_PROJECT_signingInMemoryKey=$(gpg --homedir "$work/gpg" --armor --export-secret-keys)
export ORG_GRADLE_PROJECT_signingInMemoryKeyPassword=''
version=0.0.0-publication-test
cd "$root"
./gradlew :maven-plugin:publishAllPublicationsToFluxzeroPackagesRepository \
  :gradle-plugin:publishAllPublicationsToFluxzeroPackagesRepository \
  -PappVersion="$version" -PfluxzeroPackagesUrl="file://$work/repository"
base="$work/repository/io/fluxzero/tools"
# Preserve the existing attachments: Maven/Gradle main/sources/Javadoc, and marker POM.
for coordinate in \
  "fluxzero-maven-plugin/$version/fluxzero-maven-plugin-$version.pom" \
  "fluxzero-maven-plugin/$version/fluxzero-maven-plugin-$version.jar" \
  "fluxzero-maven-plugin/$version/fluxzero-maven-plugin-$version-sources.jar" \
  "fluxzero-maven-plugin/$version/fluxzero-maven-plugin-$version-javadoc.jar" \
  "fluxzero-gradle-plugin/$version/fluxzero-gradle-plugin-$version.pom" \
  "fluxzero-gradle-plugin/$version/fluxzero-gradle-plugin-$version.jar" \
  "fluxzero-gradle-plugin/$version/fluxzero-gradle-plugin-$version-sources.jar" \
  "fluxzero-gradle-plugin/$version/fluxzero-gradle-plugin-$version-javadoc.jar" \
  "gradle/plugin/io.fluxzero.tools.gradle.plugin.gradle.plugin/$version/io.fluxzero.tools.gradle.plugin.gradle.plugin-$version.pom"; do
  gpg --homedir "$work/gpg" --verify "$base/$coordinate.asc" "$base/$coordinate"
done
scripts/verify-published-plugins.sh "$version" "file://$work/repository"
