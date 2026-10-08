# Protocol

The shared-memory layout between the Deadworks plugin (Deadlock side) and the Fabric client (Minecraft side). [`deadcraft-protocol.toml`](deadcraft-protocol.toml) is the only source of truth; everything else here is generated from it or tests against it.

| Path | What |
|---|---|
| `deadcraft-protocol.toml` | Layout: header, hero state, ability-event ring, reserved Minecraft → Deadlock region. Version, mapping name, units per block, axis map |
| `generate.py` | Writes the C# and Java bindings, the golden test values for both, and `golden/v1.bin` with its own Python encoder |
| `golden/v1.values`, `golden/v1.bin` | Hand-written test vector and its bytes. Both languages must encode the values to exactly these bytes and decode them back |
| `csharp/` | C# binding (`src/Protocol.g.cs` generated, `src/Mapping.cs` hand-written) and xUnit tests. The plugin compiles `src/*.cs` in directly |
| `java/` | Java binding (`Mapping.java` hand-written, the rest generated), JUnit tests, and the live CLI reader |

## Commands

```
python protocol/generate.py            # after editing the .toml
python protocol/generate.py --check    # fails if generated files are stale
dotnet test protocol/csharp/tests      # C# tests
protocol\java\gradlew -p protocol\java test    # Java tests
protocol\java\gradlew -p protocol\java run     # live reader (start the Deadworks server first)
```

## How it works

- **Mapping:** a named Windows file mapping, `Local\Deadcraft`, 64 KiB, opened by name through kernel32 on both sides (no file on disk, so no AppData redirection issues). Either side can create it; the creator writes the header. A side that finds another magic, version or size refuses to use it and says why.
- **Header:** magic `DCFT`, version, size, units per block (64), and each side's process id and `GetTickCount64` heartbeat. A heartbeat older than a second means that side is gone.
- **HeroState** (Deadlock → Minecraft, every server tick at 64 Hz): flags (present, alive, on ground), tick, server time, hero id, feet position, velocity, eye position, camera angles, stamina and max, health and max, newest ability-event serial. Guarded by a seqlock: `seq` is odd while the plugin writes; readers retry if it changed or was odd.
- **AbilityEvents** (Deadlock → Minecraft): a 32-entry ring of `player_used_ability` events (ability name and tick). Movement shows up here too: `citadel_ability_jump`, `dash`, `slide`, `mantle`. Readers take serials newer than the last one they saw and re-check each entry's serial after copying it.
- **Units:** positions are Source units. `Proto.ToMinecraft` / `Proto.toMinecraft` convert with the axis map (mc x = source x, mc y = source z, mc z = −source y) and `units_per_block`. Never hard-code 64.
