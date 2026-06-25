# Fluxzero Native Launchpad Apps

This directory contains platform-native Fluxzero Launchpad implementations.

- macOS: SwiftUI/AppKit, system materials, Liquid Glass where available, standard controls, native URL handling, and app-bundle packaging.
- Windows: WinUI 3, Fluent controls, Mica as the foundation layer, notification-area operation, per-user protocol registration for unpackaged builds, and MSIX-ready metadata. This implementation is present in the repo but is not published by the release workflow yet.
- Linux: planned, not implemented yet.

## Design Direction

- The macOS launcher is menu-bar-first and the Windows launcher is notification-area-first. Both only open the generator window when requested.
- The project form uses one destination menu: Codex, Claude Code, Cursor, Finder/File Explorer, or Don't open.
- Advanced fields stay collapsed by default.
- The managed CLI path/version stays as an advanced footnote.
- Project history is part of the first screen so users can quickly reopen generated work.

## URL Contract

All implementations use the same experimental URL contract:

- `fluxzero://new?...` creates a project headlessly when received from outside the app. The Launchpad window remains available from the menu-bar or notification-area item.
- `fluxzero://open?path=...&prompt=...&agent=codex|claude|cursor|finder|none` opens an existing project directly in an agent, Finder/File Explorer, or nowhere.
- `fluxzero://create?name=...&prompt=...&agent=codex|claude|cursor|finder|none` creates a project with defaults and opens it in the selected destination.

Shared query parameters:

- `name`: Project name for `new` and `create`.
- `path` or `location`: Existing project path for `open`, output directory for `new` and `create`.
- `prompt`: First prompt passed to the selected coding agent.
- `agent`: `codex`, `claude`/`claude-code`, `cursor`, `finder`/`folder`/`explorer`, or `none`/`generate`.
- `template`: Fluxzero template name.
- `groupId`, `artifactId`, `packageName`, `description`, `build`, `git`: Advanced project-generation settings.

## Build Locally

macOS:

```bash
native/macos/FluxzeroLaunchpad/build.sh
```

This creates:

```text
native/macos/FluxzeroLaunchpad/build/Fluxzero Launchpad.app
```

Windows:

```powershell
dotnet build native/windows/FluxzeroLaunchpad/FluxzeroLaunchpad.csproj
```

Windows builds require a recent .NET SDK, Windows App SDK, and Windows 10 19041+ or Windows 11.
The WinUI project references Windows App SDK `2.1.3`.

Windows core contract tests:

```bash
dotnet run --project native/windows/FluxzeroLaunchpad.Core.Tests/FluxzeroLaunchpad.Core.Tests.csproj
```

Validation from the repo root:

```bash
native/validate.sh
```

On non-Windows machines this validates XML and macOS Swift compilation, runs Windows core tests when
`dotnet` is available, and skips the Windows app build because the WinUI shell must be built inside Windows.
