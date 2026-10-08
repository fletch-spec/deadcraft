# Collider test (M4 risk spike, about 10 minutes)

Question: can a box spawned by a plugin block the hero, hold its weight, carry it while moving, and be moved every tick cheaply enough for a pool of block colliders? If not, M4 falls back to the static map from [feel-test.md](feel-test.md).

The plugin is `spikes/collider-test`. It's throwaway: once M4 exists, delete it.

## Already done on the desktop

- Deadworks `v0.5.6` installed into Deadlock (`.\tools\get-deadworks.ps1 -Install`).
- `ColliderTest.dll` built and copied to `Deadlock\game\bin\win64\managed\plugins\`.

To rebuild after a change: `dotnet build spikes/collider-test`. A running server hot-reloads it.

## Steps

1. **Start the server.** From `C:\dev\deadcraft` in PowerShell:
   ```
   .\tools\run-server.ps1
   ```
   A console window opens. Wait until it stops scrolling (map loaded). If Windows Firewall asks, allow **private networks** only.
2. **Start Deadlock** from Steam. Enable the console if needed (Settings → Options → Enable Console, then the `F7` key).
3. In the Deadlock console: `connect localhost:27067`. Pick any hero (Celeste if the list offers her) and spawn.
4. Open chat (`Enter`) and run each test below. Commands also work in the Deadlock console with a `dw_` prefix (`dw_dc_info`).

| # | Type in chat | Then do | Note down |
|---|---|---|---|
| 1 | `/dc_info` | nothing | (output is logged) |
| 2 | `/dc_box vphys crate` | Walk into the crate. Jump onto it and stand still. Dash into it | Blocked? Can you stand on it? |
| 3 | `/dc_box bbox crate` | Same as 2, on the second crate | Blocked? Stand? |
| 4 | `/dc_box vphys cube` | Same as 2 | Blocked? Stand? How big is it next to the hero? |
| 5 | `/dc_move` | Stand on the last box for 10 s | Does it lift you smoothly, jitter, or drop through you? |
| 6 | `/dc_move` again (off), then `/dc_clear` | | |
| 7 | `/dc_pool 256` | Wait 15 s. Walk through the grid | Any hitching? Are the crates solid? |
| 8 | `/dc_clear`, then `/dc_pool 1024` | Wait 15 s | Any hitching? |
| 9 | `/dc_clear` | Disconnect, close the server window | |

If a command says `no hero pawn`, you haven't spawned yet.

## Paste back

1. Every line in the **server console window** that starts with `[dc]`. Select them, right-click to copy, or copy the whole window.
2. Your notes from the table: blocked / stand / carried / hitching for each row.
3. Any red errors in the server console that mention `ColliderTest`.

## What I'll do with it

- `dc_info` gives the hero's hull and eye height in Source units. That settles units per metre for `/protocol`, and the tick rate for the bridge.
- Rows 2 to 4 pick the collider recipe (model collision or bounding box, and which model).
- Row 5 decides whether moving colliders can carry the hero, which matters for pistons and falling blocks later.
- Rows 7 and 8 set the pool size budget.

## Results (2026-10-08, Deadworks v0.5.6, Deadlock build 10931, dl_midtown, Celeste)

**Verdict: runtime colliders work. M4 goes ahead with a pool of `prop_dynamic` crates using model collision.**

| Test | Result |
|---|---|
| `dc_info` | Tick 0.015625 s (**64 Hz**). Hero hull 40 × 40 × 112 units (`BBox`), eye 86 units above the feet. Stamina, health, position, grounded and velocity read correctly |
| View angles | `ViewAngles` returns garbage (0, 0, -3.4e26). `EyeAngles` (-35.7, 34.7) and `CameraAngles` (-35.2, 35.6) agree. **Use `CameraAngles`** |
| `vphys crate` (`wood_crate_64`, 64 × 64 × 66.5) | Blocks, can be stood on, a dash from close range never goes through |
| `bbox crate` | Blocks and can be stood on, but **a dash from point-blank range phases through, repeatably**. Not usable |
| Resize `Collision.Mins/Maxs` after spawn | **Crashes the server** (access violation in coreclr). Never do it |
| `vphys crate` with `ModelScale` 2 | Visibly and physically 2 × 2 × 2 blocks; close dashes still blocked. `Collision.Mins/Maxs` still report the unscaled size, so track sizes in the plugin |
| Moving crate (`dc_move`, ±32 units, teleport every tick) | Carries the hero smoothly, no falling through |
| Pool, 256 moving every tick | 0.45 ms/tick of the 15.6 ms budget, no hitching |
| Pool, 512 moving every tick | 1.0 ms/tick |
| Pool, 1024 static | Smooth |
| Pool, 1024 moving every tick | Slight client frame lag, then the client dropped with `CNetChan::ProcessMessages: Disconnecting netchan because of excessive CPU usage`. The limit is entity updates sent to the client, not server physics |

**M4 design rules from this:**
1. `prop_dynamic` + `wood_crate_64` + `solid` = VPhysics. 64 units = 1 block, so one crate is one block.
2. Spawn the pool once. Move a collider only when the set of solid blocks near the player changes; never teleport unchanged colliders every tick.
3. Merge solid regions into cubic chunks with `ModelScale` 2, 4, ... (`ModelScale` is uniform, so only cubes) to cut the count.
4. Budget: around 1000 colliders alive, and a few dozen moved per tick at most.

**Still unverified:** the 256 pool spawned by the lane (the log confirms it) but wasn't visible, most likely because the grid always grows toward +x/+y from the spawn point. Doesn't affect the results above.
