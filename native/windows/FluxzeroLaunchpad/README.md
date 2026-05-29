# Fluxzero Launchpad for Windows

This is the platform-native Windows shell for Fluxzero Launchpad.

- UI stack: WinUI 3 through Windows App SDK.
- Visual language: Fluent, Mica window backdrop, native controls.
- Runtime posture: notification-area-first. The window stays hidden until the user chooses Create Project or Settings.
- Protocol support: `fluxzero://` is registered per user for unpackaged local builds and declared in `Package.appxmanifest` for MSIX packaging.
- Managed CLI: downloaded to `%LOCALAPPDATA%\Fluxzero\Launchpad\bin\fz.exe`.
- Managed JDK: Temurin 25+ is downloaded to `%LOCALAPPDATA%\Fluxzero\Launchpad\jdks\temurin-25` on first project generation when Java 25+ is not already available.

Build on Windows:

```powershell
dotnet build .\native\windows\FluxzeroLaunchpad\FluxzeroLaunchpad.csproj
```

Run the cross-platform URL/defaults checks:

```powershell
dotnet run --project .\native\windows\FluxzeroLaunchpad.Core.Tests\FluxzeroLaunchpad.Core.Tests.csproj
```

Useful dependency simulation flags for VM testing:

- `FLUXZERO_LAUNCHPAD_SIMULATE_MISSING_GIT=1`
- `FLUXZERO_LAUNCHPAD_SIMULATE_MISSING_JAVA=1`
- `FLUXZERO_LAUNCHPAD_JAVA_SOURCE=C:\path\to\jdk-or-zip`
- `FLUXZERO_LAUNCHPAD_JAVA_INSTALL_DIR=C:\path\to\managed-jdk`

The project references Windows App SDK `2.1.3`.
