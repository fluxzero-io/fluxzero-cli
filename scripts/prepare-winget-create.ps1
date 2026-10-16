$ErrorActionPreference = 'Stop'
$settings = Get-Content (Join-Path $PSScriptRoot '../config/publishing-tools.properties') -Raw | ConvertFrom-StringData
$version = $settings.wingetCreateVersion
if ($version -notmatch '^v[0-9]+(\.[0-9]+){2,3}$') { throw 'Invalid pinned WinGetCreate version' }
Invoke-WebRequest "https://github.com/microsoft/winget-create/releases/download/$version/wingetcreate.exe" -OutFile wingetcreate.exe
# Upstream returns 1 for --help, but prints usage and returns 0 without arguments.
# This starts the real runtime without entering first-run command/telemetry prompts.
& ./wingetcreate.exe
if ($LASTEXITCODE -ne 0) { throw 'WinGetCreate cannot start with the configured runtime' }
