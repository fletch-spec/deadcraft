# Status and handoff

Last updated 2026-10-09. Read this first when picking the project back up.

## Where it stands

A Deadlock hero (Celeste) plays in a Minecraft 26.3 singleplayer world:

- Deadlock's engine moves the hero (local Deadworks server + unmodified Deadlock client).
- The Minecraft client follows it smoothly (64 Hz samples interpolated on the server clock) and draws the world in **overlay mode**: Minecraft's window sits over Deadlock's, click-through, while Deadlock keeps the input. **F6** opens Minecraft chat; closing the screen hands input back.
- **Third-person camera** like Deadlock's: pivot at Deadlock's eye height, over the right shoulder, distance 180, right 40, up 15 (Source units), FOV matched to Deadlock's 90° horizontal.
- **Live collision:** the client sends the solid blocks around the player (merged half-block cubes); the plugin keeps a pool of colliders in step. The hero is **recentred** over the void map's floor slab so caves and long walks have no limit.
- In singleplayer the integrated server trusts the linked player's movement (no corrections, no support-block resends), which removed all flashing.

Reports: [feasibility](feasibility.md), [collider test](collider-test.md), [feel test](feel-test.md), [M2](m2-report.md), [M3](m3-report.md), [M4](m4-report.md).

## How to run it (desktop)

Shortcuts: `.\tools\make-shortcuts.ps1` puts "Deadcraft 1 - Start the movement server", "2 - Join with your hero" (Deadlock, connects on start; close Deadlock first) and "3 - Show the Minecraft world" on the desktop. The plugin sends `mat_fullbright 1` when your hero spawns on the void map. By hand:

1. `.\tools\run-server.ps1 -Map deadcraft_void` (add `-LogFile <path>` to capture the console when diagnosing crashes; the file grows fast).
2. Deadlock in **Borderless Window**: console `connect localhost:27067`, pick Celeste, `mat_fullbright 1`.
3. `fabric-client\gradlew -p fabric-client runClient`, load the creative superflat world, `/deadcraft off` then `/deadcraft on`.
4. Logs: `%TEMP%\deadcraft-plugin.log` (plugin), the dev client's `fabric-client\run\logs\latest.log`.

Commands: `/deadcraft` status, `on`/`off`, `anchor`, `overlay on|off`, `camera [on|off|<distance> <right> <up>]`.

## Hard-won rules (don't relearn these)

- Colliders: `prop_dynamic` with `citadel_center_cube_01`, model collision, uniform `ModelScale`. Never resize `Collision.Mins/Maxs` (crashes). Never force entities into a client's transmit list (crashed `engine2.dll` four times).
- Everything must stay over the void map's slab (centre 4096, 4096, top z 512): far from geometry, colliders don't collide; off its footprint, they aren't sent to clients. The map is compiled **without** visibility data (`tools/compile-map.ps1`) so nothing is culled.
- Deadlock ignores the void map's spawn entities; the plugin moves heroes found at the world origin to the slab's centre.
- Seqlock readers must spin-wait (a write takes microseconds).
- 26.3 names differ from older Minecraft: `gui.setScreen`, `Player.sendOverlayMessage`, GLFW window class isn't `GLFW30`. Verify with `javap` against the Loom jar.

## Next (phase B, then C, D, E)

1. **Camera latency** (~70 ms + 22 ms buffer): raw-mouse camera turning in Minecraft converging on Deadlock's angles, and position prediction from velocity.
2. **Hide colliders in Deadlock** and cut Deadlock's render cost (low settings, resolution, frame cap) without adding input lag.
3. **Crosshair check:** confirm abilities land on Minecraft's crosshair with the 180/40/15 camera.
4. **M5 server mod:** movement authority on a dedicated server (port the singleplayer mixins for opted-in players), Celeste's abilities, damage round trip. Laptop server to 26.3 (prompt in the conversation that set up M0), both mods into the pack.
5. **M6:** Celeste's model (extract from the player's Deadlock install at runtime, never commit), animations, other players see your hero.
6. **Release:** one-step player setup; behaviour when Deadlock updates before Deadworks.

Loose ends: untested 30-block drops and block placing/breaking; `spikes/collider-test` can be deleted; Minecraft's frame limit should match the monitor (75 here).
