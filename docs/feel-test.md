# M1 static feel test

Goal: run a Deadlock hero around a Minecraft build, with real Deadlock movement, before any bridge exists. The build becomes a compiled Deadlock map that a local Deadworks server loads.

```
Minecraft structure block ──► .nbt ──► MC2CS ──► .vmap ──► Hammer (CSDK 12) compile ──► <map>.vpk
                                                                                          │
              Deadlock client ◄── connect localhost:27067 ◄── Deadworks server +map <map> ◄┘
```

You run every step; nothing here is automated. Steps marked **(unverified)** come from community docs and haven't been run end to end. If one fails, paste the error and I'll adjust this doc.

**Scale: 64 Source units = 1 block** (decided 2026-10-08 from the collider test). The hero's hull is 112 units tall, so this makes them 1.75 blocks tall and 0.625 wide, close to a Minecraft player (1.8 × 0.6). It matches Hammer's grid, MC2CS's default and the 64-unit crate collider. `/protocol` holds the factor.

## 0. One-time setup

| Tool | Get it | Notes |
|---|---|---|
| Minecraft Java 26.3 | Any launcher, singleplayer creative | Only needs vanilla |
| Python 3.11+ | Already on the desktop (3.14) | For MC2CS |
| MC2CS | `git clone https://github.com/dotthegod/MC2CS C:\tools\MC2CS`, then `pip install -r requirements.txt` in it | GPL-3; kept outside this repo |
| CSDK 12 (Hammer for Deadlock) | [deadlockmodding.pages.dev → CSDK 12](https://deadlockmodding.pages.dev/modding-tools/csdk-12) | Community toolkit, Google Drive download. **(unverified)** |
| Deadworks | `.\tools\get-deadworks.ps1 -Install` | Already done on the desktop |

**CSDK 12 setup, condensed from its page (unverified):**
1. Unpack `Reduced_CSDK_12` somewhere outside OneDrive, e.g. `C:\tools\Reduced_CSDK_12`.
2. Map making needs full game files. Use DepotDownloader with the two commands on the CSDK page (app 1422450, depots 1422451 and 1422456), `-dir` pointing at the CSDK folder. You must own Deadlock.
3. Open `game\citadel\pak01_dir.vpk` in Source 2 Viewer, choose **Export as is** into `game\citadel`, then delete the `pak01_*.vpk` files from `game\citadel` and `game\core` **in the CSDK folder only**.
4. Re-extract the CSDK zip over the folder, replacing files.
5. Start Steam, run `csdkcfg.exe`, **Create New Addon** named `deadcraft_feel` (lowercase, no spaces). That makes `content\citadel_addons\deadcraft_feel` and `game\citadel_addons\deadcraft_feel`.

Never edit Deadlock's own files in the Steam folder. The only thing we add there is the compiled map in step 4.

## 1. Build and export in Minecraft

1. Make a creative flat world (26.3). Build a small course that exercises movement: flat run, 1-block steps, a 2-block wall, a 4-block gap, a tall tower to drop from, stairs and slabs, a 3-wide corridor, a long ramp.
2. Keep it within **48 × 48 × 48** blocks: that's the structure block limit (vanilla's limit up to 1.21; check it still applies in 26.3).
3. `/give @s structure_block`, place it at one corner, set mode **Save**, name it `feel1`, set the size to cover the build, press **Detect** or type the size, then **Save**.
4. The file is at `%APPDATA%\.minecraft\saves\<world>\generated\minecraft\structures\feel1.nbt` (or the same path under your launcher's instance folder).

## 2. Convert with MC2CS

1. `cd C:\tools\MC2CS` then `python main.py`.
2. Settings:

| Setting | Value | Why |
|---|---|---|
| Input | `feel1.nbt` | |
| Texture pack | leave empty | Placeholder material `materials/dev/reflectivity_30.vmat` exists in Deadlock, so no textures to compile or ship |
| MC assets | leave empty | Model blocks become cubes; fine for feel |
| Block Scale | **64** (the default) | See scale above |
| Cull Hidden Faces | on | |
| Output Mode | Per Chunk | |
| Stair clip ramps | on | Stairs walk like ramps |
| Liquids, trigger_hurt, ladders, slime, lighting | off | Not needed for the feel test |
| Addon export | off | |

3. **Convert.** `feel1.vmap` is written next to `feel1.nbt`. Copy it to `C:\tools\Reduced_CSDK_12\content\citadel_addons\deadcraft_feel\maps\feel1.vmap`.

## 3. Compile in Hammer (unverified)

1. `csdkcfg.exe` → select `deadcraft_feel` → **Launch Tools** → open Hammer from the tools.
2. Open `maps\feel1.vmap`. MC2CS writes CS2-format maps; if Hammer complains about the format or editor version, paste the message.
3. Keep everything inside Deadlock's map bounds. The course should sit near the origin; if it's far out, use MC2CS's **Origin Offset**.
4. Add a spawn so a hero can appear: place an `info_player_start` (and, if heroes don't spawn, an `info_team_spawn` for each team) on top of the course's start. If nothing works, skip spawns: the `/dc_tp x y z` command in the collider-test plugin teleports you onto the course.
5. Add a light (`light_environment`) or the map compiles dark. MC2CS's sky setting is CS2's; replace it if Hammer flags it.
6. **Build → Full compile.** If lighting fails, the CSDK page points at `GUIMapCompiler\CS2MapCompiler.exe`, which needs the `bin_cs2` binaries.
7. The compiled map is `game\citadel_addons\deadcraft_feel\maps\feel1.vpk`.

## 4. Run it on the local Deadworks server

1. Copy `feel1.vpk` to `C:\Program Files (x86)\Steam\steamapps\common\Deadlock\game\citadel\maps\`. Custom maps sit next to stock ones so `map <name>` finds them (Deadworks' Docker notes say the same). Steam's **Verify integrity** may delete it; just copy it back.
2. From `C:\dev\deadcraft`: `.\tools\run-server.ps1 -Map feel1`
3. Start Deadlock, open the console, `connect localhost:27067`, pick Celeste.
4. If you spawn off the course, open chat and use `/dc_tp <x> <y> <z>`. The course spans x and y from 0 to block-count × 64 and starts at z 0, unless you set an origin offset.

## 5. What to try and report

Spend 10 to 15 minutes. Paste back short notes:

- **Scale:** does a 1-block step feel like a curb, and a 2-block wall like a wall? Does a 2-high, 1-wide doorway fit Celeste?
- **Movement:** sprint, slide, dash, double jump, mantle (wall climb), stamina use. Anything that feels wrong on blocky geometry?
- **Stairs and slabs:** smooth with clip ramps on?
- **Gaps:** how many blocks can you clear with dash and jump?
- **Errors:** anything failing in steps 2 to 4, with the exact message.

## Unverified in this pipeline

1. CSDK 12 setup, Hammer opening an MC2CS (CS2-format) `.vmap`, and the full compile for Deadlock.
2. That a custom map without Deadlock's game-mode entities lets a hero spawn and move. Fallback: `/dc_tp`.
3. That a `.vpk` in `game\citadel\maps` loads with `+map` on the Windows build, as it does in Deadworks' Docker image.
4. Structure block size limit in 26.3.
