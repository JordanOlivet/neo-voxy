# setup-dev.ps1 — Windows wrapper around scripts/setup-dev.sh.
#
# Native PowerShell convenience: locates Git Bash and runs the cross-platform
# bootstrap script, forwarding any arguments (e.g. --quick, --no-build).
#
#   powershell -ExecutionPolicy Bypass -File scripts\setup-dev.ps1
#   powershell -ExecutionPolicy Bypass -File scripts\setup-dev.ps1 --quick
#
$ErrorActionPreference = "Stop"

$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$sh   = Join-Path $here "setup-dev.sh"

$bash = $null
$cmd  = Get-Command bash -ErrorAction SilentlyContinue
if ($cmd) { $bash = $cmd.Source }

if (-not $bash) {
    $candidates = @(
        "$env:ProgramFiles\Git\bin\bash.exe",
        "$env:ProgramFiles\Git\usr\bin\bash.exe",
        "${env:ProgramFiles(x86)}\Git\bin\bash.exe"
    )
    foreach ($c in $candidates) {
        if (Test-Path $c) { $bash = $c; break }
    }
}

if (-not $bash) {
    Write-Error "Git Bash not found. Install Git for Windows, or run scripts/setup-dev.sh from a bash shell."
    exit 1
}

& $bash $sh @args
exit $LASTEXITCODE
