#!/usr/bin/env pwsh
$ErrorActionPreference = 'Stop'

function Extract-OwnerRepo($repoUrl) {
    if ($repoUrl.StartsWith('https://github.com/')) {
        return $repoUrl.Substring('https://github.com/'.Length).TrimEnd('.git')
    } elseif ($repoUrl.StartsWith('git@github.com:')) {
        return $repoUrl.Substring('git@github.com:'.Length).TrimEnd('.git')
    } else {
        return $null
    }
}

function Get-ReleaseAssetUrl($repoUrl, $tag, $token) {
    $ownerRepo = Extract-OwnerRepo $repoUrl
    if (-not $ownerRepo) { throw "Could not extract owner/repo from: $repoUrl" }

    if ($tag -eq 'latest') {
        $apiUrl = "https://api.github.com/repos/$ownerRepo/releases/latest"
    } else {
        $apiUrl = "https://api.github.com/repos/$ownerRepo/releases/tags/$tag"
    }

    Write-Host "Querying GitHub Releases API: $apiUrl"

    $headers = @{ 'Accept' = 'application/vnd.github+json' }
    if ($token) {
        $headers['Authorization'] = "Bearer $token"
    }

    try {
        $release = Invoke-RestMethod -Uri $apiUrl -Headers $headers -UseBasicParsing
    } catch {
        $hint = ''
        if (-not $token) {
            $hint = "`nHINT: Set GITHUB_TOKEN to avoid rate limits (unauthenticated: 60 req/hr, authenticated: 5000 req/hr)"
        }
        throw "Failed to query GitHub Releases API at ${apiUrl}: $($_.Exception.Message)$hint"
    }

    $asset = $release.assets | Where-Object { $_.name -eq 'templates.zip' } | Select-Object -First 1
    if (-not $asset) {
        $available = ($release.assets | ForEach-Object { $_.name }) -join ', '
        throw "No templates.zip asset found in release. Available assets: $available"
    }

    return $asset.browser_download_url
}

try {
    $zipUrl = $env:EXAMPLES_ZIP_URL
    $sourceDir = $env:EXAMPLES_SOURCE_DIR
    $repoUrl = if ($env:EXAMPLES_REPO_URL) { $env:EXAMPLES_REPO_URL } else { 'https://github.com/fluxzero-io/fluxzero-examples.git' }
    $releaseTag = if ($env:EXAMPLES_RELEASE_TAG) { $env:EXAMPLES_RELEASE_TAG } else { 'latest' }
    $githubToken = $env:GITHUB_TOKEN
    $cacheDir = if ($env:CACHE_DIR) { $env:CACHE_DIR } else { './build/examples-snapshot' }
    $outputDir = if ($env:OUTPUT_DIR) { $env:OUTPUT_DIR } else { './build/generated/resources/templates' }
    $refresh = ($env:REFRESH_EXAMPLES -eq 'true')

    if ($env:DEBUG_TEMPLATES -eq 'true') {
        Write-Host "DEBUG: Using PowerShell script with:`n  zipUrl=$zipUrl`n  sourceDir=$sourceDir`n  repoUrl=$repoUrl`n  releaseTag=$releaseTag`n  cacheDir=$cacheDir`n  outputDir=$outputDir`n  refresh=$refresh"
    }

    $templateRoot = $cacheDir

    if ($sourceDir) {
        if (-not (Test-Path -LiteralPath $sourceDir -PathType Container)) {
            throw "EXAMPLES_SOURCE_DIR is not a directory: $sourceDir"
        }
        $templateRoot = (Resolve-Path -LiteralPath $sourceDir).Path
        Write-Host "Using local examples source at $templateRoot"
    } else {
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

            if (-not $zipUrl) {
                $zipUrl = Get-ReleaseAssetUrl $repoUrl $releaseTag $githubToken
            }
            if (-not $zipUrl) { throw "Could not determine ZIP URL for examples." }

            Write-Host "Downloading examples ZIP from: $zipUrl"
            $tmp = New-TemporaryFile
            try {
                $downloadHeaders = @{}
                if ($githubToken) {
                    $downloadHeaders['Authorization'] = "Bearer $githubToken"
                }
                Invoke-WebRequest -Uri $zipUrl -OutFile $tmp -Headers $downloadHeaders -UseBasicParsing | Out-Null
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
    }

    if (Test-Path $outputDir) { Remove-Item -LiteralPath $outputDir -Recurse -Force }
    New-Item -ItemType Directory -Path $outputDir | Out-Null

    if (-not (Test-Path $templateRoot -PathType Container)) { throw "Examples directory not found: $templateRoot" }
    $dirs = Get-ChildItem -LiteralPath $templateRoot -Directory -Force | Where-Object { -not $_.Name.StartsWith('.') } | Sort-Object Name
    if ($dirs.Count -eq 0) { throw "No templates found in $templateRoot" }

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $excludedRootEntries = @('build', 'target', '.gradle', '.idea')

    $index = Join-Path $outputDir 'templates.csv'
    $names = @()
    foreach ($d in $dirs) {
        $name = $d.Name
        Write-Host "Zipping template: $name"
        $dest = Join-Path $outputDir ("$name.zip")
        $stage = Join-Path ([System.IO.Path]::GetTempPath()) ("fluxzero-template-" + [guid]::NewGuid().ToString())
        New-Item -ItemType Directory -Path $stage | Out-Null
        try {
            Get-ChildItem -LiteralPath $d.FullName -Force | Where-Object {
                $excludedRootEntries -notcontains $_.Name -and $_.Name -ne '.DS_Store'
            } | ForEach-Object {
                Copy-Item -LiteralPath $_.FullName -Destination $stage -Recurse -Force
            }
            if (Test-Path -LiteralPath $dest) { Remove-Item -LiteralPath $dest -Force }
            [System.IO.Compression.ZipFile]::CreateFromDirectory($stage, $dest, [System.IO.Compression.CompressionLevel]::Optimal, $false)
        } finally {
            if (Test-Path -LiteralPath $stage) { Remove-Item -LiteralPath $stage -Recurse -Force }
        }
        $names += $name
    }
    [System.IO.File]::WriteAllLines($index, $names)

    Write-Host "Prepared templates in $outputDir"
    exit 0
} catch {
    Write-Error $_.Exception.Message
    exit 1
}
