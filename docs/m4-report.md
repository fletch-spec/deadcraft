# M4 report: live collision

2026-10-09. Minecraft 26.3 dev client in the superflat village world (`deadcraft-m3`), Deadlock on `deadcraft_void` with Celeste, Deadworks v0.5.6.

## Done

- **Protocol v2** ([deadcraft-protocol.toml](../protocol/deadcraft-protocol.toml)): Minecraft → Deadlock section.
  - `McState`: seqlock, linked flag, generation, base block and the frame offset (`hero-frame blocks = Minecraft blocks + offset`).
  - `Cubes`: up to 2048 entries of 8 bytes (half-block position relative to the base, edge 1 to 16 half blocks).
  - New generator types (`u8`, `i16`, `ivec3`, `dvec3`) and fixed arrays; the golden fixture covers them. Tests: C# 6, Java 5.
- **Fabric client** ([BlockExport](../fabric-client/src/main/java/dev/deadcraft/client/BlockExport.java), [CubeMerge](../fabric-client/src/main/java/dev/deadcraft/client/CubeMerge.java)):
  - Window: 12 blocks around the player, 8 below to 16 above, snapped to an 8-block grid.
  - Each block's collision shape is sampled into half-block cells; a box counts in a cell when it covers at least 1/8 block of it on every axis, so slabs, stairs, fences and doors keep a rough shape and carpets don't block.
  - Cells merge into grid-aligned cubes (edges 8, 4, 2, 1, 0.5 blocks at multiples of their size), so walking changes only cubes at the window's edges.
  - Republished only when the set or the frame offset changes; refreshed every 10 ticks to catch block edits. Over capacity, the nearest cubes are kept.
  - The client now creates or opens the mapping read-write and writes its heartbeat every tick.
  - Tests: 3 for the merge (exact cover, alignment, a superflat floor becoming 64 cubes of edge 4 blocks).
- **Plugin** ([ColliderPool](../deadworks-plugin/ColliderPool.cs)): one `prop_dynamic` test cube per cube, scale fixed at spawn and pooled per edge size. On a new generation, cubes that appeared take a parked collider (or spawn one), cubes that disappeared are parked at z −12000. Nothing is deleted mid-game. Cap: 2600 colliders. While the client isn't linked, colliders stay. Stats go to `%TEMP%\deadcraft-plugin.log`.

## In-game result

| Check | Result |
|---|---|
| Village walls block the hero | Yes |
| Standing on roofs | Yes |
| Hitching while walking | None noticed |
| Active colliders | 1490 to 1860 in the village; pool peaked at 2097 spawned |
| Changes per update | Small edits: tens. Crossing an 8-block grid line: bursts of 300 to 840 adds plus parks in one tick |
| Cube cap | Hit in the village: 2368 cubes, the farthest 320 dropped |

## Unverified

1. **Doors, stairs and slabs in the village.** The shapes are sampled, but not reported on in this run.
2. **Placing and breaking blocks.** They should show up in Deadlock within half a second.
3. **Window-shift bursts.** Up to about 840 collider moves in a single tick, with no hitch reported. The collider test only measured steady per-tick moves (1024 every tick flooded the client). If hitches appear, spread a burst over several ticks.
4. **Over the cap.** 2048 cubes isn't enough for the full window in a village. Options: drop cubes that are fully enclosed by other solid cubes, raise the capacity to 4096 (protocol v3, still inside the 64 KiB mapping) if Deadlock copes with more colliders, or shrink the vertical span.
5. **Going below the anchor's ground level.** The void map's floor block sits where the hero spawned, so caves, ravines and valleys lower than the anchor can't be entered. Fix: rebuild the void map with its floor far below, and move the catch net into the Deadcraft plugin.
6. **Long walks.** The Deadlock map has bounds. Walking a few hundred blocks from the anchor should eventually hit them. Fix: recentre (teleport the hero and shift the frame offset together).
7. **"Standing on air" server notices.** Still about one every 10 to 20 s, when Deadlock says grounded but Minecraft's own terrain under the player doesn't match exactly (fence tops, edges, the void floor). Harmless in singleplayer; M5's server mod should own this check.
8. **Colliders are visible in Deadlock** as grey cubes. Irrelevant once Minecraft draws over Deadlock; could be hidden with a render mode later.
