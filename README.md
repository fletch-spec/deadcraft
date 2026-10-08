# Deadcraft

Play on a Minecraft Java server as a real Deadlock hero (first target: Celeste). Deadlock's own engine supplies the movement and abilities; Minecraft supplies the world, blocks, mobs, other players and the picture you look at.

> **Status: M1 + collider spike.** M0 findings: [docs/feasibility.md](docs/feasibility.md). Waiting on in-game results from [docs/collider-test.md](docs/collider-test.md) and [docs/feel-test.md](docs/feel-test.md).

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
| `protocol/` | Shared-memory layout, single source of truth | M2 |
| `deadworks-plugin/` | C# Deadworks plugin | M2 |
| `fabric-client/` | Fabric client mod, Minecraft 26.3 | M3 |
| `fabric-server/` | Fabric server mod, Minecraft 26.3 | M5 |
| `docs/` | Feasibility, feel test, collider test | M0+ |
| `tools/` | `get-deadworks.ps1` (pinned Deadworks download and install), `run-server.ps1` (local server) | M1 |
| `spikes/collider-test/` | Throwaway plugin: can spawned boxes block the hero? | M4 spike |
| `deadworks.version` | Pinned Deadworks release every C# project builds against | |

## Setup from zero

Filled in as each component lands.

1. Windows 11, Deadlock (Steam), Minecraft Java Edition.
2. .NET 10 SDK: `winget install Microsoft.DotNet.SDK.10`
3. JDK 25 (for the Fabric mods, from M3).
4. Deadworks, pinned in `deadworks.version`. Download it and install it into Deadlock (this only adds files):
   ```
   .	ools\get-deadworks.ps1 -Install
   ```
5. Build a plugin (it deploys itself into Deadlock when Deadworks is installed):
   ```
   dotnet build spikes/collider-test
   ```
6. Start a local server, then in Deadlock's console run `connect localhost:27067`:
   ```
   .	oolsun-server.ps1
   ```

## License

MIT. See [LICENSE](LICENSE) and [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md). No Valve assets are in this repo: asset paths are config and the files are gitignored.
