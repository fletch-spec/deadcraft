# Deadcraft

Play on a Minecraft Java server as a real Deadlock hero (first target: Celeste). Deadlock's own engine supplies the movement and abilities; Minecraft supplies the world, blocks, mobs, other players and the picture you look at.

> **Status: playable in singleplayer as Celeste, with her model and movement animations; next is aiming, combat and her abilities, then multiplayer.** Start with [docs/STATUS.md](docs/STATUS.md). Celeste plays in a Minecraft world: Deadlock moves her, Minecraft draws over Deadlock's window with a Deadlock-style third-person camera, and Minecraft's blocks are live Deadlock colliders.

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
| `fabric-client/` | Fabric client mod, Minecraft 26.3: follows the Deadlock hero, vanilla fallback. `gradlew runClient` | M3 |
| `fabric-server/` | Fabric server mod, Minecraft 26.3 | M5 |
| `docs/` | Feasibility, feel test, collider test | M0+ |
| `tools/` | `get-deadworks.ps1` (pinned Deadworks), `run-server.ps1` (local server), `compile-map.ps1` (void map), `nbt-to-colliders.py` (structure → colliders), `make-shortcuts.ps1` (desktop shortcuts), `hero-export/` (a hero's model and animations from your own Deadlock install to a local .glb) | M1, M6 |
| `maps/` | `deadcraft_void.vmap`, the empty host map | M1 |
| `courses/` | Structure-block exports for the feel test | M1 |
| `spikes/collider-test/` | Throwaway plugin: can spawned boxes block the hero? | M4 spike |
| `deadworks.version` | Pinned Deadworks release every C# project builds against | |

## Setup from zero

Filled in as each component lands.

1. Windows 11, Deadlock (Steam), Minecraft Java Edition.
2. .NET 10 SDK: `winget install Microsoft.DotNet.SDK.10`
3. JDK 25 (protocol reader and Fabric mods). Python 3.11+ (protocol generator, course converter).
4. Deadworks, pinned in `deadworks.version`. Download it and install it into Deadlock (this only adds files):
   ```
   .\tools\get-deadworks.ps1 -Install
   ```
5. Build the plugin (it deploys itself into Deadlock when Deadworks is installed):
   ```
   dotnet build deadworks-plugin
   ```
6. Host map, once. Needs CSDK 12 in `C:\tools\Reduced_CSDK_12` (setup in [docs/feel-test.md](docs/feel-test.md)):
   ```
   .\tools\compile-map.ps1
   ```
7. Start a local server, then in Deadlock's console run `connect localhost:27067` and `fps_max 122` (Deadlock is hidden under Minecraft; uncapped it takes the GPU Minecraft needs):
   ```
   .\tools\run-server.ps1 -Map deadcraft_void
   ```
8. Watch the bridge live:
   ```
   protocol\java\gradlew -p protocol\java run
   ```
9. Dev Minecraft client (creative superflat world, then `/deadcraft` in chat):
   ```
   fabric-client\gradlew -p fabric-client runClient
   ```
10. Hero model, once per hero (Celeste is `unicorn`; writes `%LOCALAPPDATA%\Deadcraft\heroes\unicorn.glb`, never committed):
   ```
   dotnet run --project tools/hero-export unicorn
   ```
11. Tests: `dotnet test protocol/csharp/tests` and `protocol\java\gradlew -p protocol\java test`. After editing the protocol: `python protocol/generate.py`.

## License

MIT. See [LICENSE](LICENSE) and [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md). No Valve assets are in this repo: asset paths are config and the files are gitignored.
