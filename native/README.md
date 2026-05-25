# Fluxzero Native Launchpad Apps

This directory contains platform-native Fluxzero Launchpad implementations.

The existing Compose Desktop app remains the reference implementation for behavior while these
native shells replace the Java-like feel with platform-native UX:

- macOS: SwiftUI/AppKit, system materials, Liquid Glass where available, standard controls, native URL handling, and app-bundle packaging.
- Windows: WinUI 3, Fluent controls, Mica as the foundation layer, Acrylic/card surfaces for raised panels, per-user protocol registration for unpackaged builds, and MSIX-ready metadata.

## Design Direction

- The macOS launcher is menu-bar-first and only opens the generator window when requested.
- Primary actions are sibling choices: Open in Codex, Open in Claude Code, or Create only.
- Advanced fields stay collapsed by default.
- The managed CLI path/version stays as an advanced footnote.
- Project history is part of the first screen so users can quickly reopen generated work.

## URL Contract

All implementations use the same experimental URL contract:

- `fluxzero://new?...` creates a project headlessly on macOS when received from outside the app. The Launchpad window remains available from the menu-bar item.
- `fluxzero://open?path=...&prompt=...&agent=codex|claude|both` opens an existing project directly in an agent, or in Finder when `agent` is omitted.
- `fluxzero://create?name=...&prompt=...&agent=codex|claude|both` creates a project with defaults and opens it in an agent, or in Finder when `agent` is omitted.

Shared query parameters:

- `name`: Project name for `new` and `create`.
- `path` or `location`: Existing project path for `open`, output directory for `new` and `create`.
- `prompt`: First prompt passed to Codex or Claude.
- `agent`: `codex`, `claude`, `both`, or `none`.
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

Validation from the repo root:

```bash
native/validate.sh
```

On non-Windows machines this validates XML and macOS Swift compilation, then skips the Windows build
if `dotnet` is not available.
