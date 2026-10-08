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
