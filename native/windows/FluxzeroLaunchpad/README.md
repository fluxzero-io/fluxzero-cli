# Fluxzero Launchpad for Windows

This is the platform-native Windows shell for Fluxzero Launchpad.

- UI stack: WinUI 3 through Windows App SDK.
- Visual language: Fluent, Mica window backdrop, Acrylic/card surfaces, native controls.
- Protocol support: `fluxzero://` is registered per user for unpackaged local builds and declared in `Package.appxmanifest` for MSIX packaging.
- Managed CLI: downloaded to `%LOCALAPPDATA%\Fluxzero\Launchpad\bin\fz.exe`.

Build on Windows:

```powershell
dotnet build .\native\windows\FluxzeroLaunchpad\FluxzeroLaunchpad.csproj
```

The project references Windows App SDK `2.1.3`, the latest stable listed by Microsoft Learn on 2026-05-21.
