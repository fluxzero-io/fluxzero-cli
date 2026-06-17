# Fluxzero Maven Plugin

Maven plugin for Fluxzero project setup and Java package publishing.

## Goals

- `sync-project-files`: updates `.fluxzero/agents/` for the project's Fluxzero SDK version.
- `publish-package`: builds and publishes a Java OCI package from Maven output.

## Quick Start

### Installation

Add the plugin to your `pom.xml`:

```xml
<build>
    <plugins>
        <plugin>
            <groupId>io.fluxzero.tools</groupId>
            <artifactId>fluxzero-maven-plugin</artifactId>
            <version>1.0.0</version>
            <executions>
                <execution>
                    <goals>
                        <goal>sync-project-files</goal>
                    </goals>
                </execution>
            </executions>
        </plugin>
    </plugins>
</build>
```

This configuration runs `sync-project-files` during the Maven `initialize` phase.

## Configuration

### `sync-project-files`

`sync-project-files` updates `.fluxzero/agents/` for the SDK version used by the Maven project. It runs in the
`initialize` phase when configured as an execution, and it can also be run manually:

```bash
mvn fluxzero:sync-project-files
```

| Setting | Command-line property | Default |
|---------|-----------------------|---------|
| `enabled` | `fluxzero.projectFiles.enabled` | `true` |
| `rootProjectOnly` | `fluxzero.projectFiles.rootProjectOnly` | `true` |
| `forceUpdate` | `fluxzero.projectFiles.forceUpdate` | `false` |
| `overrideLanguage` | `fluxzero.projectFiles.overrideLanguage` | auto-detected |
| `overrideSdkVersion` | `fluxzero.projectFiles.overrideSdkVersion` | auto-detected |
| `skip` | `fluxzero.projectFiles.skip` | `false` |

### `publish-package`

`publish-package` builds and publishes a Java OCI package from compiled classes and Maven runtime dependency artifacts.

```bash
mvn -B package fluxzero:publish-package \
  -Dfluxzero.package.registryToken="$FLUXZERO_REGISTRY_TOKEN" \
  -Dfluxzero.package.name="my-service"
```

Generated Fluxzero projects put stable package settings in the POM:

```xml
<configuration>
  <packageName>my-service</packageName>
  <applicationId>...</applicationId>
</configuration>
```

Keep registry tokens and user credentials out of the POM.

| Setting | Command-line property | Environment fallback | Default |
|---------|-----------------------|----------------------|---------|
| Registry host | `fluxzero.package.registryHost` | `FLUXZERO_REGISTRY_HOST` | `registry.fluxzero.io` |
| Registry token | `fluxzero.package.registryToken` | `FLUXZERO_REGISTRY_TOKEN` | required |
| Team id | `fluxzero.team.id` | `FLUXZERO_TEAM_ID` | omitted |
| Package name | `fluxzero.package.name` | `FLUXZERO_PACKAGE_NAME` | required |
| Package version | `fluxzero.package.version` | `FLUXZERO_PACKAGE_VERSION` | generated git/time-based tag |
| Allow dirty worktree | `fluxzero.package.allowDirty` | — | `false` |
| Application id | `fluxzero.package.applicationId` | `FLUXZERO_PACKAGE_ID` | omitted |
| Main class | `fluxzero.package.mainClass` | `FLUXZERO_MAIN_CLASS` | `Start-Class` or `Main-Class` from built artifact manifest |
| Base image | `fluxzero.package.baseImage` | `FLUXZERO_BASE_IMAGE` | Fluxzero Java distroless runtime |
| Base image source | `fluxzero.package.baseImageSource` | `FLUXZERO_BASE_IMAGE_SOURCE` | `registry` |
| Skip publish | `fluxzero.package.skip` | — | `false` |
| Java tool options | `fluxzero.package.javaToolOptions` | `JAVA_TOOL_OPTIONS` | process env var, then Fluxzero JVM defaults |

The plugin rejects a dirty git worktree by default. Use `-Dfluxzero.package.allowDirty=true` for local experiments; dirty
pushes get a `-dirty` tag suffix.

Use `baseImage` for a different Java runtime image. If that image was built locally in the Docker daemon during the
same build, also set `baseImageSource` to `docker-daemon`; otherwise the plugin reads the base image from a registry.
Custom base images must provide `java` on `PATH`.

`javaToolOptions` is written to the package as `JAVA_TOOL_OPTIONS`. If the property is omitted, the plugin uses the
process `JAVA_TOOL_OPTIONS` value when it exists, otherwise it uses Fluxzero JVM defaults.

Generated Maven projects set `project.build.outputTimestamp` to `2000-01-01T00:00:00Z` unless the POM already has a
value. The package publisher also uses deterministic OCI creation and file modification timestamps for Fluxzero layers.

The package contains these labels:

- `org.opencontainers.image.title`
- `org.opencontainers.image.version`
- `io.fluxzero.maven.group-id`
- `io.fluxzero.maven.artifact-id`
- `io.fluxzero.maven.version`
- `io.fluxzero.package.metadata-version`
- `io.fluxzero.application-id`, when configured

## Local OCI Registry Test Chain

The `local-registry` directory contains a Docker Compose harness with Zot and a small local Fluxzero registry proxy.

```bash
docker compose -f maven-plugin/local-registry/docker-compose.yml up
```

In another terminal:

```bash
./gradlew :maven-plugin:publishToMavenLocal

export MAVEN_OPTS="-Djavax.net.ssl.trustStore=$PWD/.local-registry/certs/truststore-with-defaults.jks -Djavax.net.ssl.trustStorePassword=changeit"
export FLUXZERO_REGISTRY_HOST="https://127.0.0.1:8443"
export FLUXZERO_REGISTRY_TOKEN="$(node maven-plugin/local-registry/generate-token.js team-a plain-java)"
export FLUXZERO_PACKAGE_VERSION="local-dev"

mvn -B -f maven-plugin/examples/plain-java/pom.xml package fluxzero:publish-package
```

If the checkout has uncommitted changes, add `-Dfluxzero.package.allowDirty=true`; the pushed tag becomes
`local-dev-dirty`.

Inspect the backend package directly in Zot:

```bash
docker pull 127.0.0.1:5100/team-a/plain-java:local-dev
docker run --rm 127.0.0.1:5100/team-a/plain-java:local-dev codex
```

Request metrics are written to `.local-registry/proxy-metrics.ndjson`.

## Multi-Module Projects

By default, agent files are only synced in the root project to avoid duplication:

```xml
<!-- Parent pom.xml -->
<project>
    <build>
        <pluginManagement>
            <plugins>
                <plugin>
                    <groupId>io.fluxzero.tools</groupId>
                    <artifactId>fluxzero-maven-plugin</artifactId>
                    <version>1.0.0</version>
                    <configuration>
                        <rootProjectOnly>true</rootProjectOnly> <!-- default -->
                    </configuration>
                </plugin>
            </plugins>
        </pluginManagement>
        <plugins>
            <plugin>
                <groupId>io.fluxzero.tools</groupId>
                <artifactId>fluxzero-maven-plugin</artifactId>
                <executions>
                    <execution>
                        <goals>
                            <goal>sync-project-files</goal>
                        </goals>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</project>
```

To sync in every module:

```xml
<configuration>
    <rootProjectOnly>false</rootProjectOnly>
</configuration>
```

## Troubleshooting

### Plugin Not Detecting SDK Version

**Problem**: You see a warning that no Fluxzero SDK version was detected.

When the plugin cannot detect a released Fluxzero SDK version, it skips project-files sync and the build continues.

**Solutions**:

1. Ensure you have a Fluxzero SDK dependency:

```xml
<dependencies>
    <dependency>
        <groupId>io.fluxzero</groupId>
        <artifactId>fluxzero-sdk</artifactId>
        <version>1.75.1</version>
    </dependency>
</dependencies>
```

2. Or use a BOM in dependencyManagement:

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>io.fluxzero</groupId>
            <artifactId>fluxzero-bom</artifactId>
            <version>1.75.1</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <dependency>
        <groupId>io.fluxzero</groupId>
        <artifactId>fluxzero-sdk</artifactId>
    </dependency>
</dependencies>
```

3. Or use a property:

```xml
<properties>
    <fluxzero.version>1.75.1</fluxzero.version>
</properties>

<dependencies>
    <dependency>
        <groupId>io.fluxzero</groupId>
        <artifactId>fluxzero-sdk</artifactId>
        <version>${fluxzero.version}</version>
    </dependency>
</dependencies>
```

4. Or manually override:

```xml
<plugin>
    <groupId>io.fluxzero.tools</groupId>
    <artifactId>fluxzero-maven-plugin</artifactId>
    <version>1.0.0</version>
    <configuration>
        <overrideSdkVersion>1.75.1</overrideSdkVersion>
    </configuration>
</plugin>
```

### Local Snapshot SDK Versions

**Problem**: You are testing a locally built SDK such as `0-SNAPSHOT`.

Snapshot versions do not have matching release artifacts with project files, so the plugin skips sync and lets the build continue.
To sync project files anyway, temporarily set `overrideSdkVersion` to a released SDK version.

### GitHub Release or Asset Unavailable

**Problem**: The matching GitHub release or project-files asset is unavailable, or GitHub returns an API error.

Project-files sync is optional. The plugin logs a warning, skips sync, and lets the build continue.

### Wrong Language Detected

**Problem**: Plugin detects Java but you're using Kotlin (or vice versa)

**Solution**: Override the language:

```xml
<configuration>
    <overrideLanguage>kotlin</overrideLanguage> <!-- or "java" -->
</configuration>
```

Or via command line:

```bash
mvn clean install -Dfluxzero.projectFiles.overrideLanguage=kotlin
```

### Files Not Updating

**Problem**: Agent files are outdated after upgrading SDK version

**Solution**: Force an update:

```bash
mvn clean install -Dfluxzero.projectFiles.forceUpdate=true
```

Or configure force update:

```xml
<configuration>
    <forceUpdate>true</forceUpdate>
</configuration>
```

### Plugin Runs in Submodules

**Problem**: Plugin syncs files in every module, causing duplication

**Solution**: Ensure `rootProjectOnly` is enabled (default):

```xml
<configuration>
    <rootProjectOnly>true</rootProjectOnly>
</configuration>
```

## Goal Reference

### `sync-project-files`

Synchronizes AI agent instruction files for the project.

**Usage**:
```bash
mvn fluxzero:sync-project-files
```

**Phase**: INITIALIZE (runs automatically before compilation)

**Parameters**:

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `enabled` | boolean | `true` | Enable/disable plugin execution |
| `skip` | boolean | `false` | Skip plugin execution (backward compatibility) |
| `rootProjectOnly` | boolean | `true` | Only run on root project in multi-module builds |
| `forceUpdate` | boolean | `false` | Force re-download even if files exist |
| `overrideLanguage` | String | (auto) | Override language detection (`kotlin` or `java`) |
| `overrideSdkVersion` | String | (auto) | Override SDK version detection |

**Properties**:

All parameters can be set via properties:
- `fluxzero.projectFiles.enabled`
- `fluxzero.projectFiles.skip`
- `fluxzero.projectFiles.rootProjectOnly`
- `fluxzero.projectFiles.forceUpdate`
- `fluxzero.projectFiles.overrideLanguage`
- `fluxzero.projectFiles.overrideSdkVersion`

## Examples

### Basic Kotlin Project

```xml
<project>
    <dependencies>
        <dependency>
            <groupId>io.fluxzero</groupId>
            <artifactId>fluxzero-sdk</artifactId>
            <version>1.75.1</version>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.jetbrains.kotlin</groupId>
                <artifactId>kotlin-maven-plugin</artifactId>
                <version>1.9.0</version>
            </plugin>

            <plugin>
                <groupId>io.fluxzero.tools</groupId>
                <artifactId>fluxzero-maven-plugin</artifactId>
                <version>1.0.0</version>
                <executions>
                    <execution>
                        <goals>
                            <goal>sync-project-files</goal>
                        </goals>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</project>
```

### Basic Java Project

```xml
<project>
    <dependencies>
        <dependency>
            <groupId>io.fluxzero</groupId>
            <artifactId>fluxzero-sdk</artifactId>
            <version>1.75.1</version>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>io.fluxzero.tools</groupId>
                <artifactId>fluxzero-maven-plugin</artifactId>
                <version>1.0.0</version>
                <executions>
                    <execution>
                        <goals>
                            <goal>sync-project-files</goal>
                        </goals>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</project>
```

### Multi-Module Project with BOM

```xml
<!-- Parent pom.xml -->
<project>
    <properties>
        <fluxzero.version>1.75.1</fluxzero.version>
    </properties>

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>io.fluxzero</groupId>
                <artifactId>fluxzero-bom</artifactId>
                <version>${fluxzero.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>

    <build>
        <plugins>
            <plugin>
                <groupId>io.fluxzero.tools</groupId>
                <artifactId>fluxzero-maven-plugin</artifactId>
                <version>1.0.0</version>
                <configuration>
                    <rootProjectOnly>true</rootProjectOnly>
                </configuration>
                <executions>
                    <execution>
                        <goals>
                            <goal>sync-project-files</goal>
                        </goals>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</project>

<!-- Child module pom.xml -->
<project>
    <parent>
        <groupId>com.example</groupId>
        <artifactId>parent</artifactId>
        <version>1.0.0</version>
    </parent>

    <dependencies>
        <dependency>
            <groupId>io.fluxzero</groupId>
            <artifactId>fluxzero-sdk</artifactId>
            <!-- Version inherited from BOM -->
        </dependency>
    </dependencies>
</project>
```

### Manual Override Configuration

```xml
<plugin>
    <groupId>io.fluxzero.tools</groupId>
    <artifactId>fluxzero-maven-plugin</artifactId>
    <version>1.0.0</version>
    <configuration>
        <!-- Bypass all auto-detection -->
        <overrideLanguage>kotlin</overrideLanguage>
        <overrideSdkVersion>1.75.1</overrideSdkVersion>

        <!-- Force update on every build (not recommended for CI) -->
        <forceUpdate>false</forceUpdate>
    </configuration>
    <executions>
        <execution>
            <goals>
                <goal>sync-project-files</goal>
            </goals>
        </execution>
    </executions>
</plugin>
```

### Conditional Execution (CI vs Local)

```xml
<profiles>
    <profile>
        <id>ci</id>
        <build>
            <plugins>
                <plugin>
                    <groupId>io.fluxzero.tools</groupId>
                    <artifactId>fluxzero-maven-plugin</artifactId>
                    <version>1.0.0</version>
                    <configuration>
                        <!-- Disable in CI if agent files are committed -->
                        <enabled>false</enabled>
                    </configuration>
                </plugin>
            </plugins>
        </build>
    </profile>
</profiles>
```

## Requirements

- Maven 3.6 or later
- Java 11 or later
- Internet connection to sync agent files from GitHub; builds continue without syncing if GitHub is unavailable

## Support

For issues and questions:
- GitHub Issues: [flux-capacitor/flux-cli](https://github.com/flux-capacitor/flux-cli/issues)
- Documentation: [Fluxzero Docs](https://docs.fluxzero.io)

## License

See the main project LICENSE file.
