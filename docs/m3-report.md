# M3 report: Minecraft follows the Deadlock hero

2026-10-09. Minecraft 26.3 dev client (`fabric-client`, `gradlew runClient`, offline account), creative superflat singleplayer world. Deadlock on the `deadcraft_void` map with Celeste, Deadworks v0.5.6.

## Done

- **`/fabric-client`:** Fabric mod for 26.3 (Loom 1.18.3, Fabric API 0.162.0+26.3). The Java protocol binding is compiled in. `gradlew build` gives the jar; `gradlew runClient` the dev client.
- **Following:** while the bridge is live (heartbeat under 500 ms, hero present), the mod:
  - switches off vanilla movement for the local player (`LivingEntity.travel` cancelled)
  - places the player every frame at the start of `Minecraft.runTick`: anchor + the hero's displacement since anchoring, converted with the protocol's units and axes
  - copies the hero's camera angles to the player's look (Source yaw 0 = Minecraft yaw −90)
  - mirrors the hero's grounded flag and clears fall distance
  - keeps Minecraft running and at full frame rate without focus (`pauseOnLostFocus` off while linked; idle frame-rate throttle bypassed)
- **Fallback:** bridge down, Deadlock gone or `/deadcraft off`: nothing is touched and vanilla movement works. `/deadcraft on` relinks; `/deadcraft anchor` re-anchors; `/deadcraft` prints status.
- **Anchoring:** when the hero is grounded, the anchor snaps to the top of the solid block under the player, so a floating start doesn't leave the player hovering.

Every Minecraft name used was checked against the 26.3 jar with `javap` (and against SkyCraft, which targets 26.3): `LivingEntity.travel(Vec3)`, `Minecraft.runTick(boolean)`, `Entity.setPos`, `xo/yo/zo`, `xOld/yOld/zOld`, `yRotO/xRotO`, `setYHeadRot`, `yBodyRot`, `setOnGround`, `resetFallDistance`, `Options.pauseOnLostFocus`, `FramerateLimitTracker.getFramerateLimit`, `Player.sendOverlayMessage`, `BlockStateBase.getCollisionShape`, `VoxelShape.max`.

## In-game result

| Check | Result |
|---|---|
| Minecraft follows the hero | Yes, smooth |
| Turning | Correct direction |
| Delay, Deadlock → Minecraft | Felt as about 70 ms (see below) |
| Fallback (`/deadcraft off`, vanilla controls) | Works; `on` relinks |
| Link stability | First run dropped and relinked every few seconds (see fixes). After the fix: no drops in normal play |
| Flashing ground and mobs while walking | First run: the player was anchored 3 blocks up while Deadlock said "grounded", so the server resent chunks every 10 s. After the ground snap: one such warning in a 90 s run, down from one every 10 s |
| Walking through village buildings, flashing near them | Expected for M3: Minecraft's blocks don't exist in Deadlock yet, so the hero walks through them and the camera ends up inside blocks. M4 (live collision) fixes it |

## Fixes made during the test

1. **Seqlock readers gave up too early.** Both readers retried 8 times in a tight loop; a plugin write takes microseconds, so a read that landed mid-write failed and the client unlinked and re-anchored. Readers now spin-wait for up to about a millisecond (`Thread.onSpinWait` / `SpinWait`), and the client holds the last state through any gap shorter than 500 ms.
2. **Anchor snaps to the ground** under the player when the hero is grounded.

## Delay

About 70 ms by feel. It comes from following the Deadlock server's copy of the hero, which trails the Deadlock client's own predicted view by about a tick (15.6 ms) plus Deadlock's network receive margin (about 35 ms in its console), plus the shared-memory hop and Minecraft's next frame. Once Minecraft is the picture you look at (overlay mode), this reads as input lag, mostly on the camera. Options, not started:

1. **Position:** extrapolate along the hero's own velocity by the measured delay. Deadlock still owns the motion; we only predict where its server copy is about to be.
2. **Camera:** Minecraft reads raw mouse motion itself (a background window can receive raw input on Windows), turns immediately, and converges on Deadlock's angles. Biggest win, but a design change.

## Unverified

1. **One 15-second unlink** at the end of the last run (00:51:38 to 00:51:53) with no problem recorded. Either `/deadcraft off`/`on`, or the Minecraft player not being alive, or a read gap over 500 ms. Watch for it.
2. **Overlay mode** (Minecraft topmost and click-through over Deadlock): not built. M3 ran side by side.
3. **Camera height and view:** Minecraft's first-person eye (1.62 blocks) is used, not Deadlock's eye (1.34 blocks) or its over-the-shoulder camera.
4. **Real servers:** only singleplayer. On the laptop server, without the M5 server mod, Minecraft's movement checks may correct or kick a player moving at Deadlock speeds.
5. **Prism instance:** the mod has only run through `runClient`; it's not in the pack yet (and the pack's server is still 26.2).
