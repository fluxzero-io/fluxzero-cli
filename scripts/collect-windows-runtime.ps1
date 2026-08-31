#!/usr/bin/env pwsh

param(
    [Parameter(Mandatory = $true)]
    [string]$Executable,

    [Parameter(Mandatory = $true)]
    [string]$Destination
)

$ErrorActionPreference = "Stop"

$executablePath = (Resolve-Path $Executable).Path
New-Item -ItemType Directory -Path $Destination -Force | Out-Null
$destinationPath = (Resolve-Path $Destination).Path

$vswhere = Join-Path ${env:ProgramFiles(x86)} "Microsoft Visual Studio\Installer\vswhere.exe"
if (-not (Test-Path $vswhere)) {
    throw "Could not locate vswhere.exe; the Visual C++ toolchain is required to package the Windows CLI."
}

$visualStudioPath = (& $vswhere -latest -products * `
    -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 `
    -property installationPath).Trim()
if (-not $visualStudioPath) {
    throw "Could not locate a Visual Studio installation with the Visual C++ x64 tools."
}

$toolset = Get-ChildItem (Join-Path $visualStudioPath "VC\Tools\MSVC") -Directory |
    Sort-Object Name -Descending |
    Select-Object -First 1
if (-not $toolset) {
    throw "Could not locate the Visual C++ toolset."
}

$dumpbin = Join-Path $toolset.FullName "bin\Hostx64\x64\dumpbin.exe"
if (-not (Test-Path $dumpbin)) {
    throw "Could not locate dumpbin.exe in the Visual C++ x64 toolset."
}

$dumpbinOutput = & $dumpbin /DEPENDENTS $executablePath
if ($LASTEXITCODE -ne 0) {
    throw "dumpbin.exe could not inspect $executablePath."
}

$runtimeDependencies = $dumpbinOutput |
    ForEach-Object {
        if ($_ -match '^\s*((?:concrt|msvcp|vcruntime)[^\\/\s]*\.dll)\s*$') {
            $Matches[1]
        }
    } |
    Sort-Object -Unique
if (-not $runtimeDependencies) {
    throw "The Windows CLI did not declare any redistributable Visual C++ runtime dependencies."
}

$redistRoot = Join-Path $visualStudioPath "VC\Redist\MSVC"
$redistVersions = Get-ChildItem $redistRoot -Directory | Sort-Object Name -Descending
foreach ($dependency in $runtimeDependencies) {
    $source = $null
    foreach ($redistVersion in $redistVersions) {
        $crtDirectories = Get-ChildItem (Join-Path $redistVersion.FullName "x64") -Directory -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -match '^Microsoft\.VC\d+\.CRT$' }
        foreach ($crtDirectory in $crtDirectories) {
            $candidate = Join-Path $crtDirectory.FullName $dependency
            if (Test-Path $candidate) {
                $source = $candidate
                break
            }
        }
        if ($source) {
            break
        }
    }
    if (-not $source) {
        throw "Could not locate redistributable dependency $dependency."
    }
    Copy-Item $source (Join-Path $destinationPath $dependency) -Force
    Write-Host "Bundled $dependency"
}
