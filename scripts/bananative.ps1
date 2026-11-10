# Banana Native's packer on Windows: scripts\bananative.bat runs this. The same commands as scripts/bananative.command on macOS:
# on first use it finds a working environment (android/environment.yml: Python 3.14 with Tk, Capstone 5, lz4, zbic, spirv-val, the .NET 10 runtime) or makes one:
# it downloads micromamba (github.com/mamba-org/micromamba-releases) when there is none on PATH, and the packages from conda-forge, about 400 MB in all,
# into <folder>\bananative\ (asked once). The environment's path is remembered in .bananative-env in the repository (not committed). Usage:
#   scripts\bananative.bat                       the window
#   scripts\bananative.bat pack <game> [...]     scripts/pack_android.py <game folder | .nup file> [its options]
#   scripts\bananative.bat check                 check the environment's tools
#   scripts\bananative.bat setup [<folder>]      make (or remake) the environment in <folder>\bananative\
#   scripts\bananative.bat python [...]          the environment's Python, set up as for the packer
# Environment: BANANATIVE_ENV names the environment to use (the prefix of a conda environment made from android/environment.yml).
$ErrorActionPreference = 'Stop'
$Repo = Split-Path -Parent $PSScriptRoot
$Conf = Join-Path $Repo '.bananative-env'
$Yml = Join-Path $Repo 'android\environment.yml'
$Check = Join-Path $Repo 'scripts\bananative_env.py'

function Say([string]$m) { [Console]::Error.WriteLine($m) }
function Die([string]$m) { Say "[RED] $m"; exit 1 }
function Remembered { if (Test-Path $Conf) { return (Get-Content $Conf -TotalCount 1).Trim() } return '' }

function Test-Usable([string]$e) {   # an environment whose Python runs and whose tools pass bananative_env.py's check
    if (-not $e) { return $false }
    $py = Join-Path $e 'python.exe'
    if (-not (Test-Path $py)) { return $false }
    & $py $Check --quiet *> $null
    return ($LASTEXITCODE -eq 0)
}

function Find-Env {   # BANANATIVE_ENV, the remembered one, an active conda environment, then a micromamba / conda environment named bananative*
    foreach ($e in @($env:BANANATIVE_ENV, (Remembered), $env:CONDA_PREFIX)) { if (Test-Usable $e) { return $e } }
    foreach ($mm in 'micromamba', 'mamba', 'conda') {
        if (-not (Get-Command $mm -ErrorAction SilentlyContinue)) { continue }
        $list = & $mm env list 2>$null
        foreach ($line in $list) {
            $e = ($line -split '\s+' | Where-Object { $_ })[-1]
            if ($e -match '\\bananative[^\\]*$' -and (Test-Usable $e)) { return $e }
        }
    }
    return $null
}

function Get-Micromamba([string]$base) {   # one on PATH, one downloaded earlier into <base>, else downloaded now
    $c = Get-Command micromamba -ErrorAction SilentlyContinue
    if ($c) { return $c.Source }
    $exe = Join-Path $base 'micromamba.exe'
    if (Test-Path $exe) { return $exe }
    Say "downloading micromamba (win-64) into $base"
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    $ProgressPreference = 'SilentlyContinue'
    try { Invoke-WebRequest -UseBasicParsing -Uri 'https://github.com/mamba-org/micromamba-releases/releases/latest/download/micromamba-win-64' -OutFile "$exe.part" }
    catch { Die "the download failed: $_" }
    Move-Item -Force "$exe.part" $exe
    return $exe
}

function Invoke-Setup([string]$folder) {   # make the environment in <folder>\bananative\env (asked when no folder is given)
    if (-not $folder) {
        $def = Split-Path -Parent $Repo
        Say 'The packer needs its tools (Python 3.14, spirv-val, the .NET 10 runtime: about 400 MB).'
        Say 'They go into <folder>\bananative\. The drive with your game dumps is a good place.'
        $folder = Read-Host "Folder [$def]"
        if (-not $folder) { $folder = $def }
    }
    if (-not (Test-Path -PathType Container $folder)) { Die "no folder $folder" }
    $base = Join-Path (Resolve-Path $folder).Path 'bananative'
    New-Item -ItemType Directory -Force $base | Out-Null
    $mm = Get-Micromamba $base
    $envDir = Join-Path $base 'env'
    Say "making the environment $envDir from android\environment.yml (a few minutes)"
    $env:MAMBA_ROOT_PREFIX = Join-Path $base 'mamba'; $env:PIP_CACHE_DIR = Join-Path $base 'mamba\pip'
    & $mm create -y -p $envDir -f $Yml | Out-Host
    if ($LASTEXITCODE -ne 0) { Die 'micromamba create failed' }
    if (-not (Test-Usable $envDir)) { & (Join-Path $envDir 'python.exe') $Check | Out-Host; Die "the new environment's tools do not all work (above)" }
    Set-Content -Path $Conf -Value $envDir -Encoding ascii
    Say "ready: $envDir"
    return $envDir
}

$cmd = if ($args.Count -gt 0) { $args[0] } else { 'gui' }
$rest = if ($args.Count -gt 1) { $args[1..($args.Count - 1)] } else { @() }
if ($cmd -eq 'setup') { Invoke-Setup ($(if ($rest.Count) { $rest[0] } else { '' })) | Out-Null; exit 0 }

$envDir = Find-Env
if ($envDir) {
    if ((Remembered) -ne $envDir) { Set-Content -Path $Conf -Value $envDir -Encoding ascii }
} else {
    $old = Remembered
    if ($old -and (Test-Path (Join-Path $old 'python.exe'))) { Say "the environment $old lacks a tool:"; & (Join-Path $old 'python.exe') $Check }
    if ($old -match '\\bananative\\env$') { Say 'remaking it (its packages are cached)'; $envDir = Invoke-Setup (Split-Path -Parent (Split-Path -Parent $old)) }
    else { $envDir = Invoke-Setup '' }
}
$py = Join-Path $envDir 'python.exe'
$env:PATH = (@($envDir, (Join-Path $envDir 'Library\bin'), (Join-Path $envDir 'Scripts')) -join ';') + ';' + $env:PATH
$dn = Join-Path $envDir 'dotnet'
if (Test-Path $dn) { $env:DOTNET_ROOT = $dn }
switch ($cmd) {
    'gui' { & $py (Join-Path $Repo 'scripts\pack_gui.py') @rest }
    'pack' { & $py (Join-Path $Repo 'scripts\pack_android.py') @rest }
    'check' { & $py $Check }
    'python' { & $py @rest }
    default { Die "unknown command $cmd (gui, pack, check, setup, python)" }
}
exit $LASTEXITCODE
