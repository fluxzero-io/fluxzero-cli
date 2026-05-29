param(
    [switch]$Run
)

$ErrorActionPreference = "Stop"

$shareDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$zipPath = Join-Path $shareDir "fluxzero-cli-windows-test.zip"
$workRoot = Join-Path $env:USERPROFILE "FluxzeroLaunchpadTest"
$repoDir = Join-Path $workRoot "fluxzero-cli-windows-test"
$publishOut = Join-Path $shareDir "FluxzeroLaunchpad-publish"

function Invoke-Checked {
    param(
        [Parameter(Mandatory = $true)]
        [string]$FilePath,
        [Parameter(ValueFromRemainingArguments = $true)]
        [string[]]$Arguments
    )

    & $FilePath @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$FilePath exited with code $LASTEXITCODE"
    }
}

if (-not (Get-Command dotnet -ErrorAction SilentlyContinue)) {
    throw "The .NET 8 SDK is required. Install it from https://dotnet.microsoft.com/download/dotnet/8.0 and run this script again."
}

if (-not (Test-Path $zipPath)) {
    throw "Missing $zipPath"
}

Remove-Item $repoDir -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $workRoot | Out-Null
Expand-Archive -Path $zipPath -DestinationPath $workRoot -Force
Set-Location $repoDir

Invoke-Checked dotnet run --project .\native\windows\FluxzeroLaunchpad.Core.Tests\FluxzeroLaunchpad.Core.Tests.csproj
Invoke-Checked dotnet publish .\native\windows\FluxzeroLaunchpad\FluxzeroLaunchpad.csproj -c Debug -r win-arm64 --self-contained false

$exe = Join-Path $repoDir "native\windows\FluxzeroLaunchpad\bin\Debug\net8.0-windows10.0.19041.0\win-arm64\publish\FluxzeroLaunchpad.exe"
if (-not (Test-Path $exe)) {
    throw "Publish completed, but the expected executable was not found: $exe"
}

Remove-Item $publishOut -Recurse -Force -ErrorAction SilentlyContinue
Copy-Item (Split-Path -Parent $exe) $publishOut -Recurse -Force
Write-Host "Copied $publishOut"

if ($Run) {
    Start-Process (Join-Path $publishOut "FluxzeroLaunchpad.exe")
}
