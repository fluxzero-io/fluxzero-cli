# Fluxzero Maven Plugin

Maven plugin for Fluxzero projects that automatically synchronizes AI agent instruction files from GitHub releases.

## Features

- **Automatic SDK Version Detection**: Detects Fluxzero SDK version from your dependencies
- **Automatic Language Detection**: Identifies project language (Kotlin or Java)
- **Lifecycle Integration**: Runs automatically during the INITIALIZE phase
- **Multi-Module Support**: Configurable to run only on root project or all modules
- **Smart Caching**: Only downloads when version changes

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
                        <goal>sync-agent-files</goal>
                    </goals>
                </execution>
            </executions>
        </plugin>
    </plugins>
</build>
```

That's it! The plugin will automatically detect your SDK version and language, then sync agent files during the INITIALIZE phase.

## Configuration

### Minimal Configuration (Recommended)

Everything is auto-detected by default:

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
                        <goal>sync-agent-files</goal>
                    </goals>
                </execution>
            </executions>
        </plugin>
    </plugins>
</build>
```

### All Configuration Options

```xml
<plugin>
    <groupId>io.fluxzero.tools</groupId>
    <artifactId>fluxzero-maven-plugin</artifactId>
    <version>1.0.0</version>
    <configuration>
        <!-- Enable or disable the plugin (default: true) -->
        <enabled>true</enabled>

        <!-- Only run on root project in multi-module builds (default: true) -->
        <rootProjectOnly>true</rootProjectOnly>

        <!-- Force re-download even if files exist (default: false) -->
        <forceUpdate>false</forceUpdate>

        <!-- Override auto-detected language (optional) -->
        <overrideLanguage>kotlin</overrideLanguage> <!-- or "java" -->

        <!-- Override auto-detected SDK version (optional) -->
        <overrideSdkVersion>1.75.1</overrideSdkVersion>
    </configuration>
    <executions>
        <execution>
            <goals>
                <goal>sync-agent-files</goal>
            </goals>
        </execution>
    </executions>
</plugin>
```

### Disabling the Plugin

Using `enabled` (recommended):

```xml
<configuration>
    <enabled>false</enabled>
</configuration>
```

Using `skip` (backward compatibility):

```xml
<configuration>
    <skip>true</skip>
</configuration>
```

Via command line:

```bash
mvn clean install -Dfluxzero.agentFiles.enabled=false
```

Or:

```bash
mvn clean install -Dfluxzero.agentFiles.skip=true
```

## How It Works

### Automatic Detection

The plugin automatically:

1. **Detects SDK Version** from:
   - `<fluxzero.version>` property in pom.xml
   - `<fluxzero-sdk.version>` property in pom.xml
   - Direct `io.fluxzero:fluxzero-sdk` dependency version
   - `io.fluxzero:fluxzero-bom` dependency version (in dependencyManagement)

2. **Detects Language** by checking for:
   - `kotlin-maven-plugin` → Kotlin
   - `maven-compiler-plugin` → Java

3. **Downloads Agent Files** from GitHub releases matching your SDK version

4. **Extracts to `.fluxzero/` directory** in your project root

### Lifecycle Integration

The plugin runs during the `INITIALIZE` phase, which means it executes before compilation.

You can also run it manually:

```bash
mvn fluxzero:sync-agent-files
```

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
                            <goal>sync-agent-files</goal>
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

## Command Line Properties

All configuration options can be set via command line properties:

```bash
# Disable the plugin
mvn clean install -Dfluxzero.agentFiles.enabled=false

# Override language
mvn clean install -Dfluxzero.agentFiles.overrideLanguage=kotlin

# Override SDK version
mvn clean install -Dfluxzero.agentFiles.overrideSdkVersion=1.75.1

# Force update
mvn clean install -Dfluxzero.agentFiles.forceUpdate=true

# Run in all modules (not just root)
mvn clean install -Dfluxzero.agentFiles.rootProjectOnly=false
```

## Troubleshooting

### Plugin Not Detecting SDK Version

**Problem**: You see an error like "No Fluxzero SDK dependency found in project"

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
mvn clean install -Dfluxzero.agentFiles.overrideLanguage=kotlin
```

### Files Not Updating

**Problem**: Agent files are outdated after upgrading SDK version

**Solution**: Force an update:

```bash
mvn clean install -Dfluxzero.agentFiles.forceUpdate=true
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

### `sync-agent-files`

Synchronizes AI agent instruction files for the project.

**Usage**:
```bash
mvn fluxzero:sync-agent-files
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
- `fluxzero.agentFiles.enabled`
- `fluxzero.agentFiles.skip`
- `fluxzero.agentFiles.rootProjectOnly`
- `fluxzero.agentFiles.forceUpdate`
- `fluxzero.agentFiles.overrideLanguage`
- `fluxzero.agentFiles.overrideSdkVersion`

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
                            <goal>sync-agent-files</goal>
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
                            <goal>sync-agent-files</goal>
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
                            <goal>sync-agent-files</goal>
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
                <goal>sync-agent-files</goal>
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
- Internet connection (for downloading agent files from GitHub)

## Support

For issues and questions:
- GitHub Issues: [flux-capacitor/flux-cli](https://github.com/flux-capacitor/flux-cli/issues)
- Documentation: [Fluxzero Docs](https://docs.fluxzero.io)

## License

See the main project LICENSE file.
