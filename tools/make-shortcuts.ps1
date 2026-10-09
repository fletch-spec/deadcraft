# Creates desktop shortcuts for a Deadcraft session:
#   Deadcraft 1 - Server     the local Deadworks server on the void map (tools\run-server.ps1)
#   Deadcraft 2 - Deadlock   Deadlock through Steam with -console +connect localhost:27067
#   Deadcraft 3 - Minecraft  the dev Minecraft client (fabric-client runClient)
# Fullbright is turned on by the plugin when your hero spawns on the void map.
# Deadlock must be closed for shortcut 2: Steam only passes launch arguments to a fresh start.
#   .\tools\make-shortcuts.ps1
$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
$desktop = [Environment]::GetFolderPath('Desktop')
$steam = (Get-ItemProperty 'HKCU:\Software\Valve\Steam' -Name SteamExe).SteamExe -replace '/', '\'
$deadlockAppId = 1422450
$shell = New-Object -ComObject WScript.Shell

function New-Shortcut($name, $target, $arguments, $workDir, $icon) {
	$lnk = $shell.CreateShortcut((Join-Path $desktop "$name.lnk"))
	$lnk.TargetPath = $target
	$lnk.Arguments = $arguments
	$lnk.WorkingDirectory = $workDir
	if ($icon) { $lnk.IconLocation = $icon }
	$lnk.Save()
	Write-Host "Created $name"
}

New-Shortcut 'Deadcraft 1 - Server' 'powershell.exe' `
	"-NoExit -ExecutionPolicy Bypass -File `"$repo\tools\run-server.ps1`" -Map deadcraft_void" $repo $null
New-Shortcut 'Deadcraft 2 - Deadlock' $steam `
	"-applaunch $deadlockAppId -console +connect localhost:27067" (Split-Path $steam) $steam
New-Shortcut 'Deadcraft 3 - Minecraft' 'cmd.exe' `
	"/k `"$repo\fabric-client\gradlew.bat`" -p `"$repo\fabric-client`" runClient" "$repo\fabric-client" $null
