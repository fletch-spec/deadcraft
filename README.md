# Deadcraft

Play on a Minecraft Java server as a real Deadlock hero (first target: Celeste). Deadlock's own engine supplies the movement and abilities; Minecraft supplies the world, blocks, mobs, other players and the picture you look at.

> **Status: M0 (research).** No working code yet. See [docs/feasibility.md](docs/feasibility.md).

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
| `docs/` | Feasibility, feel test, design | M0+ |

## Setup from zero

Filled in as each component lands. The current requirements are:

- Windows 11
- Deadlock (Steam)
- .NET 10 SDK
- JDK 25
- Minecraft Java Edition

## License

MIT. See [LICENSE](LICENSE) and [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md). No Valve assets are in this repo: asset paths are config and the files are gitignored.
