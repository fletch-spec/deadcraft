# Deadcraft

Play on a Minecraft Java server as a Deadlock hero (first target: Celeste). Deadlock's engine supplies movement and abilities; Minecraft supplies the world and the picture.

Read [docs/STATUS.md](docs/STATUS.md) first: it is the handoff (where it stands, how to run it, what Deadlock does and doesn't tell us, the protocol signals). README.md has the architecture and layout.

## Rules

- Deadlock owns the player; Minecraft owns everything else. If the bridge is down the client falls back to vanilla movement.
- The world must always reopen in a vanilla 26.3 server: no custom blocks, items, entities or dimensions.
- Hero exports (`.dchero`, `.dcfx`, textures, models) come from the player's own Deadlock install and are never committed.
- The shared-memory layout lives in `protocol/` (one schema, generated C# and Java bindings, golden fixture). Change the schema there and regenerate; bump the protocol version.
- Every C# project builds against the Deadworks release pinned in `deadworks.version`.

## Commands

```bash
tools/run-server.ps1 -Map deadcraft_void          # local movement server
fabric-client/gradlew -p fabric-client runClient  # dev client (Minecraft 26.3)
dotnet run --project tools/hero-export unicorn    # export Celeste from your install
tools/make-shortcuts.ps1                          # desktop shortcuts
```

Logs: `%TEMP%\deadcraft-plugin.log` (plugin) and `fabric-client/run/logs/latest.log` (client).

## Status and gaps

Visual effects, combat animation and the first-round fx are in game-test pending (see STATUS.md). Multiplayer (`fabric-server/`, M5) is not built.
