param(
    [ValidateSet('arm64-v8a','x86_64')][string]$Abi = 'arm64-v8a',
    [string]$BunPath = '',
    [switch]$SkipInstall
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
if (-not $BunPath) {
    $cached = Join-Path $projectRoot '.cache\tools\bun-windows-x64\bun.exe'
    if (Test-Path -LiteralPath $cached) { $BunPath = $cached } else { $BunPath = (Get-Command bun.exe).Source }
}
$buildArguments = @((Join-Path $PSScriptRoot 'build_opencode.py'), $Abi, '--bun', $BunPath)
if ($SkipInstall) { $buildArguments += '--skip-install' }
& python @buildArguments
if ($LASTEXITCODE -ne 0) { throw "Official build failed (exit $LASTEXITCODE)" }
