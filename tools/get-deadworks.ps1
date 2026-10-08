# Download the pinned Deadworks release into .deadworks\<version>, so C# projects can build
# against it. With -Install, also copy it into Deadlock so deadworks.exe can run.
#
#   .\tools\get-deadworks.ps1             # download only (enough to build)
#   .\tools\get-deadworks.ps1 -Install    # download, then install into Deadlock
#
# Installing only adds files to Deadlock (deadworks.exe, game\bin\win64\managed\,
# game\citadel\cfg\deadworks_mem.jsonc). It refuses to overwrite a different Deadworks version
# unless you pass -Force. Deadlock's own files are never touched.
param(
    [string]$Version = (Get-Content (Join-Path $PSScriptRoot '..\deadworks.version') -Raw).Trim(),
    [string]$DeadlockDir = 'C:\Program Files (x86)\Steam\steamapps\common\Deadlock',
    [switch]$Install,
    [switch]$Force
)
$ErrorActionPreference = 'Stop'
$root = Resolve-Path (Join-Path $PSScriptRoot '..')
$dest = Join-Path $root ".deadworks\$Version"

if (-not (Test-Path (Join-Path $dest 'game\bin\win64\deadworks.exe'))) {
    New-Item -ItemType Directory -Force $dest | Out-Null
    $zip = Join-Path $dest "deadworks-$Version.zip"
    $url = "https://github.com/Deadworks-net/deadworks/releases/download/$Version/deadworks-$Version.zip"
    Write-Host "Downloading $url"
    Invoke-WebRequest $url -OutFile $zip
    Expand-Archive $zip -DestinationPath $dest -Force
    Remove-Item $zip
}
Write-Host "Deadworks $Version is in $dest"

if (-not $Install) { exit 0 }

if (-not (Test-Path (Join-Path $DeadlockDir 'game\bin\win64\deadlock.exe'))) {
    throw "Deadlock not found at $DeadlockDir. Pass -DeadlockDir."
}
$marker = Join-Path $DeadlockDir 'game\bin\win64\managed\.deadcraft-deadworks-version'
if ((Test-Path (Join-Path $DeadlockDir 'game\bin\win64\deadworks.exe')) -and -not $Force) {
    $installed = if (Test-Path $marker) { (Get-Content $marker -Raw).Trim() } else { 'unknown' }
    if ($installed -ne $Version) {
        throw "Deadworks $installed is already installed. Re-run with -Force to replace it with $Version."
    }
}
Copy-Item (Join-Path $dest 'game\*') (Join-Path $DeadlockDir 'game') -Recurse -Force
Set-Content $marker $Version -Encoding ascii
Write-Host "Installed Deadworks $Version into $DeadlockDir"
