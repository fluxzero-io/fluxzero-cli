#!/usr/bin/env pwsh
$ErrorActionPreference = 'Stop'

function Derive-ZipUrl($repoUrl, $branch) {
    if ($repoUrl.StartsWith('https://github.com/')) {
        $path = $repoUrl.Substring('https://github.com/'.Length).TrimEnd('.git')
        return "https://github.com/$path/archive/refs/heads/$branch.zip"
    } elseif ($repoUrl.StartsWith('git@github.com:')) {
        $path = $repoUrl.Substring('git@github.com:'.Length).TrimEnd('.git')
        return "https://github.com/$path/archive/refs/heads/$branch.zip"
    } else {
        return $null
    }
}

try {
    $zipUrl = $env:EXAMPLES_ZIP_URL
    $repoUrl = if ($env:EXAMPLES_REPO_URL) { $env:EXAMPLES_REPO_URL } else { 'https://github.com/fluxzero-io/fluxzero-examples.git' }
    $branch = if ($env:EXAMPLES_BRANCH) { $env:EXAMPLES_BRANCH } else { 'main' }
    $cacheDir = if ($env:CACHE_DIR) { $env:CACHE_DIR } else { './build/examples-snapshot' }
    $outputDir = if ($env:OUTPUT_DIR) { $env:OUTPUT_DIR } else { './build/generated/resources/templates' }
    $refresh = ($env:REFRESH_EXAMPLES -eq 'true')

    if ($env:DEBUG_TEMPLATES -eq 'true') {
        Write-Host "DEBUG: Using PowerShell script with:`n  zipUrl=$zipUrl`n  repoUrl=$repoUrl`n  branch=$branch`n  cacheDir=$cacheDir`n  outputDir=$outputDir`n  refresh=$refresh"
    }

    if (-not $zipUrl) { $zipUrl = Derive-ZipUrl $repoUrl $branch }
    if (-not $zipUrl) { throw "Could not determine ZIP URL for examples." }

    # Determine cache state without throwing when the directory doesn't exist
    $cacheExists = Test-Path -LiteralPath $cacheDir -PathType Container
    $hasContent = $false
    if ($cacheExists) {
        try {
            $hasContent = ((Get-ChildItem -LiteralPath $cacheDir -Force | Select-Object -First 1).Count -gt 0)
        } catch {
            $hasContent = $false
        }
    }

    if ($cacheExists -and $hasContent -and -not $refresh) {
        Write-Host "Using cached examples at $cacheDir (set REFRESH_EXAMPLES=true to refresh)"
    } else {
        if (Test-Path $cacheDir) { Remove-Item -LiteralPath $cacheDir -Recurse -Force }
        New-Item -ItemType Directory -Path $cacheDir | Out-Null

        Write-Host "Downloading examples ZIP from: $zipUrl"
        $tmp = New-TemporaryFile
        try {
            Invoke-WebRequest -Uri $zipUrl -OutFile $tmp -UseBasicParsing | Out-Null
        } catch {
            throw "Failed to download examples ZIP: $($_.Exception.Message)"
        }

        Write-Host "Unpacking examples..."
        Expand-Archive -Path $tmp -DestinationPath $cacheDir -Force
        Remove-Item -LiteralPath $tmp -Force -ErrorAction SilentlyContinue

        if (-not (Test-Path $cacheDir -PathType Container)) { throw "Examples cache directory missing after unpack: $cacheDir" }
        $entries = Get-ChildItem -LiteralPath $cacheDir -Force
        if ($entries.Count -eq 1 -and $entries[0].PSIsContainer) {
            $top = $entries[0]
            Get-ChildItem -LiteralPath $top.FullName -Force | ForEach-Object {
                $target = Join-Path $cacheDir $_.Name
                Move-Item -LiteralPath $_.FullName -Destination $target -Force
            }
            Remove-Item -LiteralPath $top.FullName -Recurse -Force
        }
    }

    if (Test-Path $outputDir) { Remove-Item -LiteralPath $outputDir -Recurse -Force }
    New-Item -ItemType Directory -Path $outputDir | Out-Null

    if (-not (Test-Path $cacheDir -PathType Container)) { throw "Examples cache directory not found: $cacheDir" }
    $dirs = Get-ChildItem -LiteralPath $cacheDir -Directory -Force | Sort-Object Name
    if ($dirs.Count -eq 0) { throw "No templates found in $cacheDir" }

    $index = Join-Path $outputDir 'templates.csv'
    $names = @()
    foreach ($d in $dirs) {
        $name = $d.Name
        Write-Host "Zipping template: $name"
        $dest = Join-Path $outputDir ("$name.zip")
        $src = Join-Path $d.FullName '*'
        Compress-Archive -Path $src -DestinationPath $dest -Force
        $names += $name
    }
    [System.IO.File]::WriteAllLines($index, $names)

    Write-Host "Prepared templates in $outputDir"
    exit 0
} catch {
    Write-Error $_.Exception.Message
    exit 1
}
