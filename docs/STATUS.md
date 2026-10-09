# Status and handoff

Last updated 2026-10-10. Read this first when picking the project back up.

## Where it stands

A Deadlock hero (Celeste) plays in a Minecraft 26.3 singleplayer world, drawn as herself:

- Deadlock's engine moves the hero (local Deadworks server + unmodified Deadlock client).
- The Minecraft client follows it (64 Hz samples interpolated on the server clock, with an adaptive delay of about 18 to 60 ms) and draws the world in **overlay mode**: Minecraft's window sits over Deadlock's, click-through, while Deadlock keeps the input. **F6** opens Minecraft chat, **Esc** its pause menu (Quit); closing the screen hands input back.
- **Third-person camera** like Deadlock's: pivot at Deadlock's eye height (eased on crouch, lowered in a slide), distance 180, right 40, up 15 (Source units), FOV matched to Deadlock's, a short FOV kick on dash.
- **Raw-mouse look:** Minecraft reads the mouse itself (raw input, listen only) and turns the camera at once; it learns Deadlock's degrees per count (0.044 here) and input lag (about 40 ms) and corrects toward Deadlock's angle every sample.
- **Hero model:** `tools/hero-export` exports the hero from the player's own Deadlock install (never committed) and packs it for Minecraft (`.dchero`: 25k triangles, 40 clips baked at 30 fps). The client skins it on the CPU (about 0.7 ms a frame) in place of the player model, at scale 1.2.
- **Animation:** `HeroAnimator` stands in for Deadlock's animation graph. Idle, 8-way run and crouch run, jump, air jump, fall, slide, dash, mantle, wall brace, turn-in-place steps. See the signal table below.
- **Live collision:** the client sends the solid blocks around the player (merged half-block cubes); the plugin keeps a pool of colliders in step, hidden (EF_NODRAW). The hero is **recentred** over the void map's floor slab so caves and long walks have no limit. While Minecraft is unlinked the hero is parked on the slab; a dead hero is respawned when it relinks.
- In singleplayer the integrated server trusts the linked player's movement (no corrections, no support-block resends).

Reports: [feasibility](feasibility.md), [collider test](collider-test.md), [feel test](feel-test.md), [M2](m2-report.md), [M3](m3-report.md), [M4](m4-report.md).

## How to run it (desktop)

Shortcuts: `.\tools\make-shortcuts.ps1` puts "Deadcraft 1 - Start the movement server", "2 - Join with your hero" (Deadlock, connects on start; close Deadlock first) and "3 - Show the Minecraft world" on the desktop. Once per hero: `dotnet run --project tools/hero-export unicorn` (Celeste). When the hero spawns on the void map the plugin sends `mat_fullbright 1` and `fps_max 122`. By hand:

1. `.\tools\run-server.ps1 -Map deadcraft_void`.
2. Deadlock in **Borderless Window**: console `connect localhost:27067`, pick Celeste.
3. `fabric-client\gradlew -p fabric-client runClient`, load the creative superflat world, `/deadcraft off` then `/deadcraft on`.
4. Logs: `%TEMP%\deadcraft-plugin.log` (plugin: hull/flags changes, recentres, colliders), the dev client's `fabric-client\run\logs\latest.log` (every animation state change and ability event, and every 10 s a readout: fps and frame times, weather, Deadlock sample age, position delay, mouse corrections, hero cost). The dev client also records Java Flight Recorder data (`run\deadcraft.jfr` on a clean quit; snapshot a running client with `jcmd <pid> JFR.dump name=1 filename=...`).

Client commands: `/deadcraft` status, `on`/`off`, `anchor`, `overlay on|off`, `camera [on|off|<distance> <right> <up>]`, `look [raw|deadlock|recalibrate]`, `delay [<ms>|0 for adaptive]`, `hero [<name>|on|off|scale <f>]`, `hud`. Server cvar: `deadcraft_deadlock_fps_max` (default 122).

## What Deadlock tells us (and what it doesn't)

Deadworks exposes no animation state, so every animation needs a rule over these signals:

| Signal | Source | Meaning |
|---|---|---|
| position, velocity, on ground | HeroState | movement |
| eye height | HeroState | 86 standing and sliding, 55 crouched (Celeste) |
| hull height | HeroState (v4) | 112 standing, 64 crouched or sliding (also mid-mantle) |
| entity flags | HeroState (v4) | bit 0 on ground, bit 1 ducking |
| ability events | event ring | `citadel_ability_jump/_dash/_mantle`; `_slide` fires on every **crouch press**, not just slides |

A slide is hull low with the eye up. Wall proximity comes from Minecraft's own blocks.

## Hard-won rules (don't relearn these)

- Colliders: `prop_dynamic` with `citadel_center_cube_01`, model collision, uniform `ModelScale`, hidden by setting EF_NODRAW in `m_fEffects` after spawn (the `rendermode` keyvalue is ignored). Never resize `Collision.Mins/Maxs` (crashes). Never force entities into a client's transmit list (crashed `engine2.dll` four times).
- Everything must stay over the void map's slab (centre 4096, 4096, top z 512). The map is compiled **without** visibility data. Never recentre while Minecraft is unlinked.
- The GPU is shared: Deadlock uncapped (fps_max 400) took Minecraft from ~460 to ~70 fps while moving. Deadlock at 122 leaves Minecraft ~350 fps moving and still reads input smoothly (30 felt sluggish). A command sent as a client disconnects never arrives, so the normal cap is restored by `fps_max 400` in Deadlock's `game\citadel\cfg\autoexec.cfg` (added on this PC; a Steam verify may empty it).
- Rain costs vanilla Minecraft a lot at 3440x1440 (~93 fps).
- Seqlock readers must spin-wait. The plugin retries the bridge every 2 s, so a protocol bump only needs a Minecraft restart.
- 26.3 names: `gui.setScreen`, `Player.sendOverlayMessage`, `calculateFov` is on `Camera`, entity rendering is `submit` + `SubmitNodeCollector.submitCustomGeometry`, HUD is Fabric's `HudElementRegistry` with `GuiGraphicsExtractor`. Verify with `javap` against the Loom jar (beware piping two classes into one grep).
- Clips carry root motion on the `root_motion` joint; the exporter cancels its travel (not its turn).

## Next

1. **Animations still missing:** aiming up and down (aim layers over the base pose), combat stance and shooting (needs a weapon-fire signal), her four abilities (ability events), landing and run-to-stop, mantle height variants, hit reactions and death (Deadworks `OnTakeDamage`). The head can't turn on its own because clips are baked to final skinning matrices; per-joint local transforms in the pack would allow head look and better blending.
2. **Crosshair check:** confirm abilities land on Minecraft's crosshair with the 180/40/15 camera.
3. **Sliding on stairs and slopes** (user request): stairs as tilted colliders so Deadlock's own slide works, with settings for which steps count and how strong the slide is.
4. **M5 server mod:** movement authority on a dedicated server (port the singleplayer mixins), Celeste's abilities, damage round trip via stand-in targets in each player's Deadlock, other players drawn as their heroes. Laptop server to 26.3, both mods into the pack.
5. **Hunger Games mode** for 6 to 10 friend-picked heroes, then all heroes; settings screen with per-PC balance (Deadlock frame cap).
6. **Release:** one-step player setup (shortcuts, hero export, autoexec line); behaviour when Deadlock updates before Deadworks.

Loose ends: untested 30-block drops and block placing/breaking; `spikes/collider-test` can be deleted; the CPU skinning could move to the GPU if many heroes are on screen.
