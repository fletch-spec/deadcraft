# Compile a Hammer map with CSDK 12's bin_cs2 resourcecompiler and install it into Deadlock.
# Hammer's own Build Map (F9) fails in CSDK 12 with a particleslib schema mismatch; this works.
#   .\tools\compile-map.ps1                     # maps\deadcraft_void.vmap
#   .\tools\compile-map.ps1 -Map other_map
param(
    [string]$Map = 'deadcraft_void',
    [string]$Csdk = 'C:\tools\Reduced_CSDK_12',
    [string]$DeadlockDir = 'C:\Program Files (x86)\Steam\steamapps\common\Deadlock'
)
$ErrorActionPreference = 'Stop'
$repo = Resolve-Path (Join-Path $PSScriptRoot '..')
$content = Join-Path $Csdk "content\citadel_addons\deadcraft\maps\$Map.vmap"
# The map source lives in the repo; the compiler needs it inside the CSDK's content tree.
New-Item -ItemType Directory -Force (Split-Path $content) | Out-Null
Copy-Item (Join-Path $repo "maps\$Map.vmap") $content -Force
& (Join-Path $Csdk 'game\bin_cs2\win64\resourcecompiler.exe') -retail -breakpad -nompi -nop4 -world -phys -vis `
    -outroot (Join-Path $Csdk 'game') -i $content
if ($LASTEXITCODE) { throw "resourcecompiler failed ($LASTEXITCODE)" }
$vpk = Join-Path $Csdk "game\citadel_addons\deadcraft\maps\$Map.vpk"
Copy-Item $vpk (Join-Path $DeadlockDir 'game\citadel\maps') -Force
Write-Host "Installed $Map.vpk. Start the server with: .\tools\run-server.ps1 -Map $Map"
