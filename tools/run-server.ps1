# Start a local Deadworks server for testing. Uses Deadworks' documented default launch line
# (docs.deadworks.net, "Run a server"); launch options replace all of its defaults, so the
# whole line is here. -nomaster keeps the server off Deadworks' public server list. Connect from Deadlock's console with: connect localhost:27067
param(
    [string]$Map = 'dl_midtown',
    [int]$Port = 27067,
    [string]$DeadlockDir = 'C:\Program Files (x86)\Steam\steamapps\common\Deadlock'
)
$ErrorActionPreference = 'Stop'
$bin = Join-Path $DeadlockDir 'game\bin\win64'
if (-not (Test-Path (Join-Path $bin 'deadworks.exe'))) {
    throw 'deadworks.exe not found. Run .\tools\get-deadworks.ps1 -Install first.'
}
$launch = @(
    '-dedicated', '-console', '-dev', '-insecure', '-allow_no_lobby_connect', '-nomaster',
    '+tv_citadel_auto_record', '0', '+spec_replay_enable', '0', '+tv_enable', '0',
    '+citadel_upload_replay_enabled', '0', '+con_logfile', 'deadcraft_server.log', '+hostport', $Port, '+map', $Map
)
Start-Process -FilePath (Join-Path $bin 'deadworks.exe') -ArgumentList $launch -WorkingDirectory $bin
Write-Host "Deadworks starting on port $Port with map $Map. In Deadlock's console: connect localhost:$Port"
