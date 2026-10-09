# Creates desktop shortcuts for a Deadcraft session, run in order:
#   Deadcraft 1 - Start the movement server   local Deadworks server on the void map (tools/run-server.ps1)
#   Deadcraft 2 - Join with your hero         Deadlock through Steam, connecting to that server
#   Deadcraft 3 - Show the Minecraft world    the dev Minecraft client, drawn over Deadlock
# Deadlock must be closed for shortcut 2: Steam only passes launch arguments to a fresh start.
#
# Shortcut 2 also caps Deadlock's frame rate: its picture is hidden under Minecraft, and uncapped it
# takes the GPU Minecraft needs (fps_max 400 left Minecraft ~70 fps while moving; 122 left ~350 and
# still reads input smoothly; 30 felt sluggish). Pick the balance for your PC with -DeadlockFps.
# Deadlock saves the cap, so normal launches need "fps_max 400" in Deadlock's
# game\citadel\cfg\autoexec.cfg (it runs before the shortcut's own commands).
# The server can't set it: Deadlock ignores fps_max and mat_fullbright sent by a server.
#   .\tools\make-shortcuts.ps1 [-DeadlockFps 122]
param([int]$DeadlockFps = 122)
$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
$desktop = [Environment]::GetFolderPath('Desktop')
$steam = (Get-ItemProperty 'HKCU:\Software\Valve\Steam' -Name SteamExe).SteamExe -replace '/', '\'
$deadlockAppId = 1422450
$shell = New-Object -ComObject WScript.Shell
# Shortcuts from earlier versions of this script
'Deadcraft 1 - Server', 'Deadcraft 2 - Deadlock', 'Deadcraft 3 - Minecraft' |
	ForEach-Object { Remove-Item (Join-Path $desktop "$_.lnk") -ErrorAction SilentlyContinue }

function New-Shortcut($name, $target, $arguments, $workDir, $icon) {
	$lnk = $shell.CreateShortcut((Join-Path $desktop "$name.lnk"))
	$lnk.TargetPath = $target
	$lnk.Arguments = $arguments
	$lnk.WorkingDirectory = $workDir
	if ($icon) { $lnk.IconLocation = $icon }
	$lnk.Save()
	Write-Host "Created $name"
}

New-Shortcut 'Deadcraft 1 - Start the movement server' "$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe" `
	"-NoExit -ExecutionPolicy Bypass -File `"$repo\tools\run-server.ps1`" -Map deadcraft_void" $repo $null
New-Shortcut 'Deadcraft 2 - Join with your hero' $steam `
	"-applaunch $deadlockAppId -console +fps_max $DeadlockFps +connect localhost:27067" (Split-Path $steam) $steam
# The window closes with Minecraft, or stays open to show the error if the build or game fails.
# cmd /c strips the outermost pair of quotes, so the whole command line is wrapped in one more pair.
New-Shortcut 'Deadcraft 3 - Show the Minecraft world' "$env:SystemRoot\System32\cmd.exe" `
	"/c `"`"$repo\fabric-client\gradlew.bat`" -p `"$repo\fabric-client`" runClient || pause`"" "$repo\fabric-client" "$repo\tools\icons\grass-block.ico,0"
