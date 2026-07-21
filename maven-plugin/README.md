# Fluxzero Maven Plugin

Maven plugin for Fluxzero project setup and Java package publishing.

## Goals

- `sync-project-files`: updates `.fluxzero/agents/` for the project's Fluxzero SDK version.
- `publish-package`: builds and publishes a Java OCI package from Maven output.
- `dev`: starts the local Fluxzero development environment and keeps it running until interrupted.

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

### `dev`

`dev` is the project-local fallback for starting the same environment as `fz dev`, without requiring a globally
installed CLI. The goal resolves the newest compatible `io.fluxzero.tools:fluxzero-dev-server` release, then starts
it independently and attaches a live event view:

```bash
./mvnw fluxzero:dev
```

Type `q`/`quit` and press Enter to open a menu, use the arrow keys to choose between detaching, stopping everything,
and returning to the live view, and press Enter to confirm. Type `d`/`detach` and press Enter to leave the environment
running. `Ctrl-C` stops the environment and all applications; an unexpected terminal disconnect only detaches the view. Set
`-Dfluxzero.dev.background=true` to skip the attached view.
A detached environment is controlled with `fz dev attach`, `fz dev status`, `fz dev logs --follow`, and `fz dev stop`;
only one session can run per project.

| Name | Required | Default | Description |
|------|----------|---------|-------------|
| `devServerVersion` | No | Active project pin or newest stable `1.x` release | Dev-server version override; property `fluxzero.dev.serverVersion`. |
| `devMainClass` | No | Auto-detected | Main-class override; property `fluxzero.dev.mainClass`, with `FLUXZERO_MAIN_CLASS` as environment fallback. |
| `devApplicationName` | No | Maven artifact id | Application name; property `fluxzero.dev.applicationName`. |
| `applications` | No | All discovered applications | Applications or named configurations to start; property `fluxzero.dev.applications`. |
| `environment` | No | `local` | Application environment/profile; property `fluxzero.dev.environment`. |
| `port` | No | Dynamic | Preferred public gateway port; property `fluxzero.dev.port`. |
| `idp` | No | `managed` | IDP mode; property `fluxzero.dev.idp`. |
| `devNamespace` | No | Project default | Fluxzero namespace; property `fluxzero.dev.namespace`. |
| `watch` | No | `true` | Watch backend sources; property `fluxzero.dev.watch`. |
| `compileOnStart` | No | `true` | Compile before the initial launch; property `fluxzero.dev.compileOnStart`. |
| `testsEnabled` | No | `true` | Run background tests; property `fluxzero.dev.testsEnabled`. |
| `fastCompiler` | No | `false` | Enable the Maven-correct fast Java compiler; property `fluxzero.dev.fastCompiler`. |
| `frontendCommand` | No | — | Managed frontend command; property `fluxzero.dev.frontendCommand`. |
| `frontendDirectory` | No | Project root | Working directory for frontend commands; property `fluxzero.dev.frontendDirectory`. |
| `frontendSetupCommand` | No | — | One-time frontend setup command; property `fluxzero.dev.frontendSetupCommand`. |
| `frontendUrl` | No | — | URL of an externally managed frontend; property `fluxzero.dev.frontendUrl`. |
| `frontendEnabled` | No | `true` | Enable frontend integration; property `fluxzero.dev.frontendEnabled`. |
| `backendPaths` | No | Empty | Repeatable backend paths routed unchanged to Fluxzero. |
| `appArgs` | No | Empty | Repeatable arguments passed to the application process. |
| `startupTimeoutMillis` | No | `20000` | Application readiness timeout; property `fluxzero.dev.startupTimeoutMillis`. |
| `gracefulShutdownTimeoutMillis` | No | `5000` | Graceful application shutdown timeout; property `fluxzero.dev.gracefulShutdownTimeoutMillis`. |
| `debounceMillis` | No | `300` | Source-watcher debounce; property `fluxzero.dev.debounceMillis`. |
| `background` | No | `false` | Start detached instead of attaching a live view; property `fluxzero.dev.background`. |
| `skipDev` | No | `false` | Skip the dev environment; property `fluxzero.dev.skip`. |

Stable shared environment, frontend, application flavor, 1Password-reference, and startup-command configuration belongs
in tracked `.fluxzero/dev.yaml`; the Maven settings above are local or invocation-specific overrides.

Agent plugins should use `fz mcp` directly. Maven is deliberately not used as an MCP stdio launcher because its
own stdout would corrupt the MCP protocol stream.

### `sync-project-files`

`sync-project-files` updates `.fluxzero/agents/` for the SDK version used by the Maven project. It runs in the
`initialize` phase when configured as an execution, and it can also be run manually:

```bash
mvn fluxzero:sync-project-files
```

| Name | Required | Default | Description |
|------|----------|---------|-------------|
| `enabled` | No | `true` | Enable project-file synchronization; property `fluxzero.projectFiles.enabled`. |
| `rootProjectOnly` | No | `true` | Run only for the Maven execution root; property `fluxzero.projectFiles.rootProjectOnly`. |
| `forceUpdate` | No | `false` | Re-download and rewrite project files; property `fluxzero.projectFiles.forceUpdate`. |
| `overrideLanguage` | No | Auto-detected | Override language detection with `kotlin` or `java`; property `fluxzero.projectFiles.overrideLanguage`. |
| `overrideSdkVersion` | No | Auto-detected | Override SDK-version detection; property `fluxzero.projectFiles.overrideSdkVersion`. |
| `skip` | No | `false` | Legacy opt-out; property `fluxzero.projectFiles.skip`. Prefer `enabled=false`. |

### `publish-package`

`publish-package` builds and publishes a Java OCI package from compiled classes and Maven runtime dependency artifacts.

```bash
mvn -B package fluxzero:publish-package
```

Generated Fluxzero projects put stable package and registry settings in the POM. Use Maven interpolation for values that
come from CI or the local environment.

```xml
<configuration>
  <packageName>my-service</packageName>
  <applicationId>...</applicationId>
  <images>
    <image>registry.fluxzero.io/958e1ee2f6c64facbc7765026a9a6e09/my-service</image>
  </images>
  <tags>
    <tag>${project.version}</tag>
    <tag>sha-${env.GITHUB_SHA}</tag>
  </tags>
  <authentications>
    <authentication>
      <host>registry.fluxzero.io</host>
      <github-oidc>
        <audience>https://cloud.fluxzero.io</audience>
      </github-oidc>
    </authentication>
  </authentications>
</configuration>
```

Grant the GitHub Actions job `id-token: write` when using `github-oidc`. Keep literal registry tokens and user credentials
out of the POM. Refer to environment variables through Maven interpolation instead.

General behavior can still be overridden from the command line or environment:

| Name | Required | Default | Description |
|------|----------|---------|-------------|
| `allowDirty` | No | `false` | Permit publishing a dirty worktree; property `fluxzero.package.allowDirty`. |
| `mainClass` | No | Built artifact `Start-Class` or `Main-Class` | Main class; property `fluxzero.package.mainClass`, environment fallback `FLUXZERO_MAIN_CLASS`. |
| `baseImage` | No | Fluxzero Java distroless runtime | Runtime base image; property `fluxzero.package.baseImage`, with `FLUXZERO_BASE_IMAGE` as environment fallback. |
| `baseImageSource` | No | `registry` | Base-image source; property `fluxzero.package.baseImageSource`, environment fallback `FLUXZERO_BASE_IMAGE_SOURCE`. |
| `skipPackagePublish` | No | `false` | Skip package publishing; property `fluxzero.package.skip`. |
| `javaToolOptions` | No | Process `JAVA_TOOL_OPTIONS`, then Fluxzero JVM defaults | Value written to `JAVA_TOOL_OPTIONS`; property `fluxzero.package.javaToolOptions`. |

Package and registry configuration is Maven configuration only:

| Name | Required | Default | Description |
|------|----------|---------|-------------|
| `packageName` | Yes | — | Public package name. |
| `applicationId` | No | — | Fluxzero application id stored as package metadata. |
| `packageVersion` | No | Generated git/time-based tag | Primary package tag when `tags` is omitted. |
| `images` | Yes | — | One or more explicit image repositories. |
| `tags` | No | `packageVersion`, or its generated value | Tags applied to every configured image. |
| `authentications` | No | Anonymous access | Optional host-bound authentication; unmatched target registries remain anonymous. |

To publish the same package to multiple repositories or tags, configure `images` and `tags`. Add authentication only
for target registries that require it.

```xml
<configuration>
  <packageName>my-service</packageName>
  <images>
    <image>registry.fluxzero.io/${env.FLUXZERO_ORGANISATION_ID}/my-service</image>
    <image>ghcr.io/${env.GITHUB_REPOSITORY}-${project.artifactId}</image>
  </images>
  <tags>
    <tag>${project.version}</tag>
    <tag>sha-${env.GITHUB_SHA}</tag>
  </tags>
  <authentications>
    <authentication>
      <host>registry.fluxzero.io</host>
      <github-oidc>
        <audience>https://cloud.fluxzero.io</audience>
      </github-oidc>
    </authentication>
    <authentication>
      <host>ghcr.io</host>
      <basic>
        <username>${env.GITHUB_ACTOR}</username>
        <token>${env.GITHUB_TOKEN}</token>
      </basic>
    </authentication>
  </authentications>
</configuration>
```

| Name | Required | Default | Description |
|------|----------|---------|-------------|
| `authentication.host` | Yes | — | Lowercase registry host with optional port; schemes and paths are rejected. |
| `authentication.basic` | No | — | Basic username/password mechanism. Configure exactly one of `basic` or `github-oidc`. |
| `authentication.github-oidc` | No | — | GitHub Actions OIDC mechanism. Configure exactly one of `basic` or `github-oidc`. |
| `basic.username` | No | Empty | Registry username. |
| `basic.token` | Yes | — | Registry password or token; required when `basic` is configured. |
| `github-oidc.username` | No | Empty | Registry username associated with the requested token. |
| `github-oidc.audience` | Yes | — | OIDC audience; required when `github-oidc` is configured. |

Authentication is optional. Every configured image is matched to an authentication by exact registry host and port;
when no match exists, Jib accesses that target anonymously. This also permits an authenticated private registry and an
anonymous public registry in the same publish execution. Duplicate authentications for the same host and configured
authentications that match no image are configuration errors. Authentications describe how the plugin obtains a
username/password credential for Jib; they are not themselves necessarily long-lived credentials. Tags and images form
a Cartesian product: every tag is published to every image. Jib performs one containerization per image and attaches the
remaining tags to that same push.

`basic` sends the configured username and token to Jib as registry username/password credentials. Its username defaults
to empty; configure it for registries that use the username as part of authentication:

```xml
<authentication>
  <host>registry.example.com</host>
  <basic>
    <username>${env.REGISTRY_USERNAME}</username>
    <token>${env.REGISTRY_TOKEN}</token>
  </basic>
</authentication>
```

`github-oidc` asks GitHub Actions for a short-lived OIDC token and uses it as the registry password. The request URL and
request bearer token are read only from `ACTIONS_ID_TOKEN_REQUEST_URL` and `ACTIONS_ID_TOKEN_REQUEST_TOKEN`, which GitHub
injects when the job has `id-token: write`. They cannot be redirected through POM configuration.

```xml
<authentication>
  <host>registry.fluxzero.io</host>
  <github-oidc>
    <audience>https://cloud.fluxzero.io</audience>
  </github-oidc>
</authentication>
```

The `github-oidc` username also defaults to empty and can be overridden with an optional `<username>` child. Its
`<audience>` is required: it identifies the Fluxzero OIDC verifier and is intentionally not derived from the target
registry host. A directly available CI OIDC token is a `basic` token; use Maven interpolation to put it in `<token>`.

The plugin rejects a dirty git worktree by default. Use `-Dfluxzero.package.allowDirty=true` for local experiments; dirty
pushes get a `-dirty` tag suffix.

Use `baseImage` for a different Java runtime image. If that image was built locally in the Docker daemon during the
same build, also set `baseImageSource` to `docker-daemon`; otherwise the plugin reads the base image from a registry.
Custom base images must provide `java` on `PATH`.

`javaToolOptions` is written to the package as `JAVA_TOOL_OPTIONS`. If the property is omitted, the plugin uses the
process `JAVA_TOOL_OPTIONS` value when it exists, otherwise it uses Fluxzero JVM defaults.

Generated Maven projects set `project.build.outputTimestamp` to `2000-01-01T00:00:00Z` unless the POM already has a
value. The package publisher also uses deterministic OCI creation and file modification timestamps for Fluxzero layers.
Runtime dependencies are written to the container classpath explicitly. Application classes come first, then Maven runtime
dependencies in Maven's runtime classpath order, comparable to `exec:java`; the dependency layer uses the same order.

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
export FLUXZERO_REGISTRY_HOST="127.0.0.1:8443"
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

| Name | Required | Default | Description |
|------|----------|---------|-------------|
| `enabled` | No | `true` | Boolean that enables or disables plugin execution. |
| `skip` | No | `false` | Legacy Boolean opt-out retained for compatibility. |
| `rootProjectOnly` | No | `true` | Boolean that limits execution to the Maven execution root. |
| `forceUpdate` | No | `false` | Boolean that forces a re-download even when files exist. |
| `overrideLanguage` | No | Auto-detected | String override: `kotlin` or `java`. |
| `overrideSdkVersion` | No | Auto-detected | String SDK-version override. |

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
