# fluxzero-cli 

A command-line interface for [Flux](https://fluxcapacitor.io/) that helps you scaffold new projects, perform dependency upgrades, generate example code, and manage Flux Cloud resources.

## Installation

### Option 1: Automated Installation (Recommended)

**Unix/Linux/macOS:**
```bash
curl -sSL https://github.com/flux-capacitor-io/flux-cli/releases/latest/download/install.sh | sh
```

**Windows PowerShell:**
```powershell
iwr -useb https://github.com/flux-capacitor-io/flux-cli/releases/latest/download/install.ps1 | iex
```

The installer will:
- 🎯 **Auto-detect** your platform and architecture
- 📦 **Download native binary** (no Java required)
- ⚡ **Install to `~/.fluxzero/bin/fz`** 
- 🔗 **Add to PATH** (optional - you'll be prompted)
- ✅ **Verify installation** with test commands

### Option 2: Manual Native Executable Download

Download the native executable for your platform from the [releases page](https://github.com/flux-capacitor-io/flux-cli/releases):

- **Linux x86_64**: `flux-linux-amd64`
- **macOS Intel**: `flux-macos-amd64`
- **macOS Apple Silicon**: `flux-macos-arm64`
- **Windows x64**: `flux-windows-amd64.exe`

```bash
# Example for macOS Apple Silicon
curl -L -o fz https://github.com/flux-capacitor-io/flux-cli/releases/latest/download/flux-macos-arm64
chmod +x fz
sudo mv fz /usr/local/bin/fz
```

**Benefits of native executables:**
- ⚡ **5x faster startup** (~0.27s vs ~1.36s for JAR)
- 📦 **Self-contained** (no Java installation required)
- 🚀 **Instant execution** (no JVM warm-up time)
- 🌍 **Cross-platform** (Linux, macOS, Windows)

### Option 3: Manual JAR Installation (Legacy)

1. Download the latest `fluxzero-cli.jar` from the [releases page](https://github.com/flux-capacitor-io/flux-cli/releases)
2. Run it with Java: `java -jar fluxzero-cli.jar`

## Uninstallation

To remove FluxZero CLI from your system:

**Unix/Linux/macOS:**
```bash
curl -sSL https://github.com/flux-capacitor-io/flux-cli/releases/latest/download/uninstall.sh | sh
```

**Windows PowerShell:**
```powershell
iwr -useb https://github.com/flux-capacitor-io/flux-cli/releases/latest/download/uninstall.ps1 | iex
```

The uninstaller will:
- 🔍 **Detect installations** (both current `.fluxzero` and legacy `.flux` directories)
- 📋 **Show what will be removed** with confirmation prompts
- 🗑️ **Clean removal** of binaries, directories, and PATH entries
- ✨ **Complete cleanup** leaves no traces

Add `--force` (Unix) or `-Force` (Windows) to skip confirmation prompts.

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

## Installation Location

FluxZero CLI installs to:
- **Directory**: `~/.fluxzero/bin/fz` (or `fz.exe` on Windows)
- **PATH Integration**: 
  - **Unix**: `/usr/local/bin/fz` → `~/.fluxzero/bin/fz`
  - **Windows**: `~/.fluxzero/bin` added to user PATH
- **Legacy Support**: Also detects and can upgrade from old `.flux/flux-cli.jar` installations

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
- **Multi-platform builds** via GitHub Actions for Linux, macOS, and Windows (ARM64/x86_64)
- **Native image compilation** using GraalVM for optimal performance
- **Automated installation** with platform detection and PATH integration
- **Per-project versioning** via `.flux/config.yaml`

## CI/CD

- **Native Build Workflow**: Builds native executables for Linux, macOS, and Windows with integration testing
- **Release Workflow**: Auto-versioning with git tags and comprehensive artifact releases
- **Automated testing**: 
  - Unit tests with MockK
  - Integration tests (CLI functionality + project generation)
  - Installation script verification
  - Cross-platform build verification
- **Release Artifacts**:
  - Native executables for all platforms
  - JAR for cross-platform compatibility
  - Installation and uninstallation scripts

## License

This project is licensed under the MIT License - see the LICENSE file for details.

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.