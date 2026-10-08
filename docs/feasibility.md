# M0 feasibility

Research spike, 2026-10-08. No code written. Sources were read at these versions:

| Source | Version read |
|---|---|
| Deadworks (`Deadworks-net/deadworks`, `main`) | commit `9d67333` (2026-10-07), latest release `v0.5.6` |
| docs.deadworks.net | features/entities, abilities, damage (2026-10-08) |
| SkyCraft (`chasmlol/SkyCraft`, `main`) | `docs/DESIGN.md`, `protocol/skycraft_protocol.h` (2026-10-08) |
| Fabric meta + Modrinth | 2026-10-08 |

**Verdict: feasible, go to M1.** Every question has a positive answer in the SDK source. The biggest risk is (b): the API to build runtime colliders exists, but nobody has shown a pawn standing on one, so the first in-game test is a 1-block collider. If runtime colliders fail, M1's static pipeline is the fallback, so (b) is not a stop.

---

## a) Read pawn position, velocity and angles every tick: **Yes**

Plugins implement `IDeadworksPlugin` / `DeadworksPluginBase` (`managed/DeadworksManaged.Api/IDeadworksPlugin.cs`).

- **Per-tick hook:** `void OnGameFrame(bool simulating, bool firstTick, bool lastTick)`.
- **Finding the pawn:** `Entities.ByClass<CCitadelPlayerPawn>()`, or `OnPawnHeroInitialized(CCitadelPlayerPawn pawn)` to catch it when it spawns.
- **Fields** (`Entities/CBaseEntity.cs`, `Entities/CCitadelPlayerPawn.cs`):

| Need | API | Notes |
|---|---|---|
| Position | `Vector3 Position` | Scene node `AbsOrigin` (feet) |
| Eye position | `Vector3 EyePosition` | AbsOrigin + ViewOffset; camera goes here |
| Velocity | `Vector3 AbsVelocity { get; set; }` | |
| View angles | `Vector3 ViewAngles` | Raw `v_angle` from CUserCmd, full precision. `EyeAngles` is 11-bit quantized |
| Camera angles | `Vector3 CameraAngles` | |
| Grounded | `bool IsOnGround`, `CBaseEntity? GroundEntity`, `EntityFlags Flags` | `IsOnGround` = ground handle valid |
| Move type | `MoveType MoveType` | |
| Stamina | `float GetStamina()`, `SetStamina(float)` | `AbilityComponent.ResourceStamina.CurrentValue` |
| Health | `int Health { get; set; }`, `int GetMaxHealth()` | |
| Hero | `Heroes HeroID` | |
| Raw input | `OnProcessUsercmds(ProcessUsercmdsEvent)` gives `List<CCitadelUserCmdPB>` | Lets us see buttons per cmd |

Plugins are .NET 10 assemblies (`TargetFramework net10.0`), so `System.IO.MemoryMappedFiles` is available for the bridge. A plugin can also read any schema field by class and field name with `SchemaAccessor<T>(className, fieldName)`, for anything not wrapped.

## b) Spawn solid colliders and move them per tick: **API yes, behaviour unverified**

What exists (`CBaseEntity.cs`, `CCollisionProperty.cs`, `Enums/SolidType.cs`, docs `/features/entities`):

- **Create:** `CBaseEntity.CreateByDesignerName("prop_dynamic")`, then `Spawn(CEntityKeyValues)` with `model` and `origin`. `prop_dynamic_override`, `prop_physics`, `func_brush` and `func_movelinear` are all in the entity database.
- **Shape:** `Collision.Mins` and `Collision.Maxs` are settable. `SetSolid(SolidType.BBox)` gives "an axis-aligned box from the entity's bounds", but the body is only rebuilt by `SetModel` with a *different* model.
- **Layers:** `SetCollisionGroup`, `AddInteractsAs/With/Exclude(InteractionLayer)`, `EnableCollision`/`DisableCollision`, `SolidFlags`.
- **Move:** `Teleport(position, angles, velocity)` takes nulls to keep values.
- **Remove:** `Remove()`. Docs warn not to remove an entity on the tick it was created.
- **Precache:** models must be added in `OnPrecacheResources` with `Precache.AddResource("...vmdl")`.

**Plan for M4: a collider pool.** Spawn N `prop_dynamic` entities once at map load, each with a box model (or `BBox` sized 1 m with a model swap to force the rebuild). Each tick, teleport pool members onto the solid blocks near the pawn and park unused ones far away. Never create or destroy entities per tick. Merge runs of blocks into larger boxes to keep N small.

**Not proven, so first M4 test:**
1. Does a `prop_dynamic` with `SolidType.BBox` or a VPhysics box model block the hero's movement and let it stand on top?
2. Does teleporting it every tick update the physics body without hitches?
3. How many colliders before the server tick time suffers?
4. Which stock Deadlock model is a clean axis-aligned box? It's referenced by path in config and never committed.

**Fallbacks if runtime colliders fail:**
1. **Static map (M1 pipeline):** export the MC area as `.nbt`, convert with MC2CS to `.vmap`, compile in Hammer. That's solid and fast, but not live; rebuild per area.
2. **Hybrid:** static map for the terrain, plus a small pool only for blocks placed or broken during play.
3. **Trace-and-correct:** the plugin traces the pawn's move against MC blocks (`TraceSystem`, hull traces) and corrects position or velocity. This edges toward reimplementing physics in the plugin, so it's the last resort.

## c) Observe ability casts and set pawn health: **Yes**

- **Before a cast:** `OnAbilityAttempt(AbilityAttemptEvent)` gives `PlayerSlot`, `HeldButtons`, `ChangedButtons`, and can `Block(...)` or `Force(...)` buttons.
- **After a cast:** game event `player_used_ability` (`player`, `caster`, `abilityname`, `annotation`, from `game_exported/game.gameevents`), handled with `[GameEventHandler("player_used_ability")]`.
- **Ability state:** `GetAbilityBySlot(EAbilitySlot)`, then `IsOnCooldown`, `CooldownStart`, `CooldownEnd`, `RemainingCharges`, `MaxCharges` (docs `/features/abilities`). `ResetAbilityCooldown(slot)` and `ExecuteAbilityBySlot(slot)` exist too.
- **Health:** `Health` is settable. `Heal(float)` and `Hurt(damage, attacker, inflictor, ability, damageType)` exist, and `TakeDamage(CTakeDamageInfo)` takes flags (`PreventDeath`, `SuppressDamageModification`, ...).
- **Incoming damage:** `OnTakeDamage(TakeDamageEvent)` can scale damage or block it with `HookResult.Stop`.
- **Barrier:** there is no wrapped property. `ModifierState.BarrierActive` and `EModifierEvent.BarrierAdded` / `UnitShieldAbsorbedDamage` exist, and the raw field can be read with `SchemaAccessor`. The exact schema field name is unverified. To keep Deadlock's own barrier math, forward MC damage with `Hurt()` rather than setting `Health`.

## d) Fabric for 26.3 as a release: **Yes**

- Minecraft `26.3` is `stable: true` on meta.fabricmc.net. Snapshots for 26.4 are out but are not used.
- Fabric Loader `0.19.5` is stable.
- Fabric API `0.162.0+26.3` is a `release` on Modrinth (2026-10-06).
- Loom: SkyCraft builds on 26.3 with `net.fabricmc.fabric-loom` `1.18-SNAPSHOT`. Check for a non-snapshot Loom at M3.
- 26.3 is unobfuscated, so no Yarn mappings are needed.

**Consequence for mc-mod:** the laptop's modded server is on 26.2. Moving it to 26.3 is a pack change (`pack.toml`) plus a deploy on the laptop, needed before M5.

## e) SkyCraft's input focus: **OS focus on the game that draws; MC hidden but told it's focused**

From `docs/DESIGN.md` §7:
- The Skyrim window has OS focus and draws everything.
- The SKSE plugin reads raw input, swallows it from Skyrim's controls, and forwards it into MC's `KeyboardHandler` / `MouseHandler` through Mixins.
- The MC window is hidden but told it is focused, so it doesn't pause or release the mouse.
- Routing modes: gameplay (to MC), MC screen open (to MC, cursor in overlay), Skyrim menu open (to Skyrim, MC frozen).

**This does not port directly.** SkyCraft injects into Skyrim, and we may not inject into the Deadlock client. Our roles also flip: Deadlock must get the input (its own movement and prediction) while Minecraft draws the picture.

**Proposed for Deadcraft:**
- **Deadlock window keeps OS focus** and receives raw input normally, borderless windowed, never exclusive fullscreen. Nothing is injected.
- **The MC window is a topmost, click-through, non-activating overlay** over Deadlock (`WS_EX_TOPMOST | WS_EX_TRANSPARENT | WS_EX_NOACTIVATE | WS_EX_LAYERED`, set by the Fabric client on its own window via GLFW's native handle). It draws the world and never takes focus.
- **The MC client is told it's focused** (as SkyCraft does) so it doesn't pause, and its own movement input is ignored while the bridge is live.
- **MC screens** (chat, inventory, pause): the client drops the click-through style and takes focus; on close, it hands focus back to Deadlock.
- **Deadlock menus or deaths:** the plugin reports the state, and the client shows a frozen view or lets Deadlock show through.

---

## Unverified

Nothing below has been run. Each needs a test in game.

1. **(b) Colliders block hero movement.** Whether a spawned `prop_dynamic` with `BBox`/VPhysics stops the pawn and can be stood on, and whether per-tick `Teleport` keeps the physics body in sync. First test in M4.
2. **(b) Collider budget.** How many pool entities a local server handles at its tick rate.
3. **Server tick rate** of a Deadworks server, which sets the bridge update rate.
4. **Camera latency.** MC draws from server-side pawn state, which lags the Deadlock client's predicted view by about one tick plus network interpolation. Mouse look may feel delayed. Measure in M3; if it's bad, look at the client's own view through allowed means (spectating, `CameraAngles`), never injection.
5. **Overlay focus trick.** Whether Deadlock keeps raw mouse input and renders with a topmost click-through window over it, and whether Deadlock throttles itself when covered.
6. **Units.** Source traditionally uses 1 unit = 1 inch (39.37 units per metre). Not confirmed for Deadlock. The factor goes in `/protocol` and gets checked in M1 by measuring a hero against a 1-block cube.
7. **Barrier field name** on the hero pawn (for reading barrier through `SchemaAccessor`).
8. **Empty map.** Which stock map to run the pawn in before M1 produces one.
9. **Deadworks stability.** The README says early development, APIs change without notice, and Deadworks may need a new release after each Deadlock update. Pin a version per milestone.
10. **Building Deadworks.** Needs VS 2026 (C++ and .NET workloads), .NET 10 and protobuf 3.21.8. Releases ship `deadworks-vX.zip`; whether plugins can build against that zip alone, without building the native side, is still to check in M2.

## Setup state on the desktop

- .NET SDK 10.0.401: installed 2026-10-08.
- JDK 25 (Temurin 25.0.4.1): present.
- Deadlock: installed via Steam.
- Visual Studio 2026 and protobuf: not checked. Only needed to build Deadworks itself, which the release zip may avoid.
