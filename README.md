# fluxzero-cli 

A command-line interface for [Flux](https://fluxcapacitor.io/) that helps you scaffold new projects, perform dependency upgrades, generate example code, and manage Flux Cloud resources.

## Installation

### Option 1: Native Executables (Recommended)

Download the native executable for your platform from the [releases page](https://github.com/flux-capacitor-io/flux-cli/releases):

- **Linux x86_64**: `flux-linux-amd64`
- **Linux ARM64**: `flux-linux-arm64` 
- **macOS Intel**: `flux-macos-amd64`
- **macOS Apple Silicon**: `flux-macos-arm64`

```bash
# Example for macOS Apple Silicon
curl -L -o flux https://github.com/flux-capacitor-io/flux-cli/releases/latest/download/flux-macos-arm64
chmod +x flux
sudo mv flux /usr/local/bin/fz
```

**Benefits of native executables:**
- ⚡ **5x faster startup** (~0.27s vs ~1.36s for JAR)
- 📦 **Self-contained** (no Java installation required)
- 🚀 **Instant execution** (no JVM warm-up time)

### Option 2: Install Script (JAR-based)

```bash
curl https://flux-capacitor/install.sh | sh
```

During installation you will be prompted to create `/usr/local/bin/fz` so the CLI is available on your `PATH`.

### Option 3: Manual JAR Installation

1. Download the latest `fluxzero-cli.jar` from the [releases page](https://github.com/flux-capacitor-io/flux-cli/releases)
2. Run it with Java: `java -jar fluxzero-cli.jar`

## Project Versioning

The fluxzero-cli uses per-project versioning rather than global installation. Each project determines which version of the CLI it uses in the `.flux/config.yaml` file.

## Usage

Once installed, you can use the CLI with the `fz` command:

```bash
# Initialize a new project (interactive template selection)
fz init my-project

# List available templates
fz templates list

# Show version
fz version

# Upgrade CLI to latest version
fz upgrade
```

See `fz --help` for more information on available commands.

## Requirements

- **Native executables**: No requirements (self-contained)
- **JAR version**: Java 21 or higher

## Templates

All templates are located in this repository under the `templates/` directory. Templates are fully working examples that are customized by the CLI to match your preferences.

**Template features:**
- Package name replacement
- File removal based on configuration
- Line-by-line content modification
- Interactive customization during project creation

Available templates:
- `flux-kotlin-single` - Single-module Kotlin project
- `flux-java-single` - Single-module Java project  
- `gamerental` - Example multi-feature application

## Development

### Building the CLI

```bash
./gradlew build shadowJar
```

### Building Native Executable

Requires GraalVM with native-image support:

```bash
# Switch to GraalVM (example using jenv)
jenv local oracle64-21.0.1

# Build native executable
./gradlew nativeCompile

# Test the native executable
./build/native/nativeCompile/flux version
```

### Running

```bash
./gradlew run
# or
java -jar build/libs/fluxzero-cli-dev-all.jar version
```

### Testing

```bash
./gradlew test
```

## Architecture

- **Kotlin-based** with Clikt for command-line parsing and JLine for interactive prompts
- **Template system** with ZIP-based project scaffolding and YAML-based customization
- **Multi-platform builds** via GitHub Actions for Linux and macOS (ARM64/x86_64)
- **Native image compilation** using GraalVM for optimal performance
- **Per-project versioning** via `.flux/config.yaml`

## CI/CD

- **Native Build Workflow**: Builds native executables for all platforms
- **Release Workflow**: Auto-versioning with git tags and multi-artifact releases
- **Automated testing**: Unit tests with MockK and build verification

## License

This project is licensed under the MIT License - see the LICENSE file for details.

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.