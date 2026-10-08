# Deadcraft

Play on a Minecraft Java server as a real Deadlock hero (first target: Celeste). Deadlock's own engine supplies the movement and abilities; Minecraft supplies the world, blocks, mobs, other players and the picture you look at.

> **Status: M2 done, next M3.** Reports: [feasibility](docs/feasibility.md), [collider test](docs/collider-test.md), [feel test](docs/feel-test.md), [M2](docs/m2-report.md). The plugin streams the Deadlock hero's state over shared memory at 64 Hz and a Java reader prints it live.

## How it works

```
 your PC                                                        server host
┌──────────────────────────────────────────────────────┐      ┌────────────────────┐
│ Deadlock client ──► local Deadworks server            │      │ Minecraft 26.3     │
│   (has focus,         └─ deadworks-plugin (C#)        │      │  + fabric-server   │
│    unmodified)              │ shared memory           │      │                    │
│                             ▼                         │      │                    │
│ Minecraft client + fabric-client (Java) ──────────────┼─────►│                    │
│   (draws the world as an overlay)                     │      └────────────────────┘
└──────────────────────────────────────────────────────┘
```

- Deadlock owns the player: physics, stamina, dash, ability timing.
- Minecraft owns everything else. If the bridge is down, you get vanilla movement.
- Each player runs their own Deadlock + Deadworks + Minecraft client and joins a shared Minecraft server.
- The world always reopens in a vanilla 26.3 server: no custom blocks, items, entities or dimensions.

## Layout

| Path | What | Milestone |
|---|---|---|
| `protocol/` | Shared-memory layout (one schema, generated C# and Java bindings, golden fixture), live reader. [README](protocol/README.md) | M2 |
| `deadworks-plugin/` | C# Deadworks plugin: writes hero state and ability casts every tick | M2 |
| `fabric-client/` | Fabric client mod, Minecraft 26.3 | M3 |
| `fabric-server/` | Fabric server mod, Minecraft 26.3 | M5 |
| `docs/` | Feasibility, feel test, collider test | M0+ |
| `tools/` | `get-deadworks.ps1` (pinned Deadworks), `run-server.ps1` (local server), `compile-map.ps1` (void map), `nbt-to-colliders.py` (structure → colliders) | M1 |
| `maps/` | `deadcraft_void.vmap`, the empty host map | M1 |
| `courses/` | Structure-block exports for the feel test | M1 |
| `spikes/collider-test/` | Throwaway plugin: can spawned boxes block the hero? | M4 spike |
| `deadworks.version` | Pinned Deadworks release every C# project builds against | |

## Setup from zero

Filled in as each component lands.

1. Windows 11, Deadlock (Steam), Minecraft Java Edition.
2. .NET 10 SDK: `winget install Microsoft.DotNet.SDK.10`
3. JDK 25 (for the Fabric mods, from M3).
4. Deadworks, pinned in `deadworks.version`. Download it and install it into Deadlock (this only adds files):
   ```
   .\tools\get-deadworks.ps1 -Install
   ```
5. Build a plugin (it deploys itself into Deadlock when Deadworks is installed):
   ```
   dotnet build spikes/collider-test
   ```
6. Start a local server, then in Deadlock's console run `connect localhost:27067`:
   ```
   .\tools\run-server.ps1
   ```

## License

MIT. See [LICENSE](LICENSE) and [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md). No Valve assets are in this repo: asset paths are config and the files are gitignored.
