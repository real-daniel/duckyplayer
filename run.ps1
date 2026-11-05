$ErrorActionPreference = 'Stop'
$executable = Join-Path $PSScriptRoot 'build\package\Ducky Player\Ducky Player.exe'
if (-not (Test-Path -LiteralPath $executable)) {
    & (Join-Path $PSScriptRoot 'build.ps1')
}
Start-Process -FilePath $executable
