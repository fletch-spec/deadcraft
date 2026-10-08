# M2 report: protocol, plugin, live reader

2026-10-09. Deadworks v0.5.6, Deadlock build 10931, map `deadcraft_void`, hero Celeste (`Unicorn`, hero id 81).

## Done

- **`/protocol`:** one schema ([deadcraft-protocol.toml](../protocol/deadcraft-protocol.toml)) generating the C# and Java bindings and a golden fixture. See [protocol/README.md](../protocol/README.md).
- **Tests:** C# 5 passing (golden encode, golden decode, unit and axis conversion, shared-memory round trip, ring wrap-around). Java 4 passing (golden encode, golden decode, conversion, shared-memory round trip through kernel32 via the Foreign Function API). Both sides produce byte-identical output for the golden values, which a third encoder in `generate.py` wrote.
- **`/deadworks-plugin`:** writes hero state every tick and appends `player_used_ability` events for the local human's hero. `dotnet build deadworks-plugin` builds and deploys it.
- **Live reader:** `protocol\java\gradlew -p protocol\java run`.

## In-game result

The reader ran for about 5 minutes (564 lines at 2 per second) while Celeste moved around the feel-test course:

| Action | Seen in the reader |
|---|---|
| Standing | ticks +32 per 0.5 s (64 Hz), speed 0, `ground`, stamina 4/4 |
| Walk, sprint, dash | position moving; speed up to 9.9 blocks/s on dashes; stamina 4 → 3.04 → 1.32, then regenerating |
| Jump, double jump | `air`, vertical speed +291 to −266 units/s, about 4 blocks of height, back to `ground` |
| Look up and down | camera pitch −89 and +89 |
| Abilities 1 to 4 | `ability_unicorn_radiantblast`, `_prismaticguard`, `_luminousstrike`, `_dazzlingorb` |
| Movement | also arrives as events: `citadel_ability_jump`, `_dash`, `_slide`, `_mantle` |
| Health | 690/690, later 1845/1845 |

No stale heartbeat and no failed seqlock read in the whole run. The plugin (C#) and the reader (Java) agree on the layout end to end.

## Unverified

1. **Minecraft creating the mapping first.** The Java `Mapping.openOrCreate` path is covered by a test within one process, but not against a live plugin that opens it second.
2. **Several humans on one Deadworks server.** The plugin bridges only the first and logs a warning. One server per player is the design; not exercised.
3. **Hero swap and respawn.** The plugin re-finds the pawn when its handle goes invalid. Not exercised in this run (no death or swap happened).
4. **Plugin unload.** On unload the plugin writes a "no hero" state so readers stop following a gone pawn. Not exercised.
5. **Ability-ring overrun.** A reader that falls more than 32 events behind reports the gap. The reader here polls every 0.5 s and never fell behind; the Fabric client will read every frame.
6. **`ViewAngles`.** Still garbage in Deadworks v0.5.6; the plugin sends `CameraAngles` instead (it matched `EyeAngles` in the collider test). Recheck on Deadworks updates.
7. **Hero id 81.** Earlier notes guessed 60 for Celeste; the live value is 81. The golden fixture's 60 is arbitrary test data.

## Not in M2 (by design)

Minecraft → Deadlock data (block colliders, incoming damage) has a reserved region at 0x1000 and arrives in M4/M5 with a protocol version bump.
