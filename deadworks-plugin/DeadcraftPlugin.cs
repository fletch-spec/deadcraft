using System.Numerics;
using System.Runtime.Versioning;
using Deadcraft.Protocol;
using DeadworksManaged.Api;

[assembly: SupportedOSPlatform("windows")]

namespace Deadcraft.Plugin;

// Deadlock side of the bridge. Every server tick it writes the local player's hero state into the
// shared-memory block (protocol/deadcraft-protocol.toml), and it records their ability casts.
// One human player per Deadworks server: each Deadcraft player runs their own local server.
public class DeadcraftPlugin : DeadworksPluginBase
{
	public override string Name => "Deadcraft";

	private Mapping? _mapping;
	private readonly ColliderPool _colliders = new();
	private readonly List<Cube> _cubes = new();
	private uint _cubeGeneration;
	private uint _recenterSerial;
	private Vector3 _recenterDelta;
	private string _cheatsMap = "";

	// The void map (maps/deadcraft_void.vmap): one floor slab, 8192 units square, centred on
	// (4096, 4096), top at z 512. Its spawn entities aren't accepted by Deadlock (it falls back to
	// the world origin, the slab's corner), so the plugin places spawning heroes itself.
	private static readonly Vector3 SlabCentreTop = new(4096f, 4096f, 512f);
	// Recentring keeps the hero inside a box around Home, over the slab: Deadlock only collides with
	// and sends clients entities over the map's geometry (docs/m4-report.md). Falling out of the box
	// recentres again, so drops have no depth limit and walks no length limit.
	private static readonly Vector3 Home = SlabCentreTop + new Vector3(0f, 0f, 1500f);
	private const float MaxHorizontal = 3000f, MaxVertical = 700f;
	private const string VoidMap = "deadcraft_void";
	private float _aliveSince = -1f;
	private CCitadelPlayerPawn? _hero;
	private bool _warnedSeveralHumans;
	private bool _clientLinked;
	private bool _reportedHideProblem;
	private long _nextBridgeAttempt;
	private string? _bridgeProblem;
	private float _lastHull;
	private uint _lastEntityFlags;

	public override void OnLoad(bool isReload) => TryOpenBridge();

	// A mapping held open by a Minecraft client on another protocol version can't be used; retry until
	// that client closes (or restarts on a matching build) instead of needing a server restart.
	private void TryOpenBridge()
	{
		_nextBridgeAttempt = Environment.TickCount64 + 2000;
		try
		{
			_mapping = Mapping.OpenOrCreate();
			Log($"bridge open: {Proto.MappingName}, protocol v{Proto.Version}");
			_bridgeProblem = null;
		}
		catch (Exception e)
		{
			if (e.Message != _bridgeProblem) Log($"bridge disabled, retrying every 2 s: {e.Message}");
			_bridgeProblem = e.Message;
		}
	}

	public override void OnPrecacheResources() => Precache.AddResource(ColliderPool.Model);

	// The void map has no baked light: turn on fullbright for each player's client as their hero spawns
	// (a cheat convar; sv_cheats is on for this map).
	public override void OnPawnHeroInitialized(CCitadelPlayerPawn pawn)
	{
		if (Server.MapName != VoidMap || pawn.IsBot || pawn.Controller == null) return;
		Server.ExecuteCommand("sv_cheats 1");
		Server.ClientCommand(pawn.Controller.Slot, "mat_fullbright 1");
		int original = DeadlockFrameCap.Original();
		Server.ClientCommand(pawn.Controller.Slot, $"fps_max {DeadlockFrameCap.WhilePlaying}");
		Log($"mat_fullbright 1 and fps_max {DeadlockFrameCap.WhilePlaying} sent to slot {pawn.Controller.Slot} (their own fps_max {original} is restored on disconnect)");
	}

	// Put the player's own frame cap back as they leave (see DeadlockFrameCap).
	public override void OnClientDisconnecting(ClientDisconnectedEvent e)
	{
		if (Server.MapName != VoidMap) return;
		int original = DeadlockFrameCap.Original();
		Server.ClientCommand(e.Slot, $"fps_max {original}");
		Log($"fps_max {original} sent back to slot {e.Slot} as it disconnects");
	}

	public override void OnUnload()
	{
		_colliders.Clear();
		if (_mapping == null) return;
		_mapping.WriteHeroState(new HeroState());  // flags 0: no hero
		_mapping.Dispose();
		_mapping = null;
	}

	public override void OnGameFrame(bool simulating, bool firstTick, bool lastTick)
	{
		if (_mapping == null && Environment.TickCount64 >= _nextBridgeAttempt) TryOpenBridge();
		if (_mapping == null) return;
		_mapping.DeadlockHeartbeat();
		if (Server.MapName != _cheatsMap)
		{
			// The void map has no baked light; sv_cheats lets players use mat_fullbright 1.
			_cheatsMap = Server.MapName;
			if (_cheatsMap == VoidMap) Server.ExecuteCommand("sv_cheats 1");
		}
		var hero = FindHero();
		bool alive = hero != null && hero.IsAlive;
		if (!alive) _aliveSince = -1f;
		else if (_aliveSince < 0) _aliveSince = GlobalVars.CurTime;
		// Deadlock rejects the void map's spawn entities and spawns heroes at the world origin, the
		// slab's corner, sometimes twice per spawn. Nothing else ever puts a hero there (home is over
		// the slab's centre), so a hero near the origin has just spawned: move it to the centre.
		if (alive && Server.MapName == VoidMap && Math.Abs(hero!.Position.X) < 300f && Math.Abs(hero.Position.Y) < 300f)
		{
			hero.Teleport(position: SlabCentreTop + new Vector3(0f, 0f, 16f), velocity: Vector3.Zero);
			Log($"moved the spawning hero from {hero.Position} to the middle of the floor slab");
		}
		if (alive) MaybeRecenter(hero!);
		var state = new HeroState { Tick = (ulong)GlobalVars.TickCount, ServerTime = GlobalVars.CurTime };
		if (hero != null)
		{
			var flags = HeroFlags.Present;
			if (hero.IsAlive) flags |= HeroFlags.Alive;
			if (hero.IsOnGround) flags |= HeroFlags.OnGround;
			var stamina = hero.AbilityComponent.ResourceStamina;
			state.Flags = (uint)flags;
			state.HeroId = (uint)hero.HeroID;
			state.Position = hero.Position;
			state.Velocity = hero.AbsVelocity;
			state.EyePosition = hero.EyePosition;
			// ViewAngles reads garbage in Deadworks v0.5.6; CameraAngles matches EyeAngles (docs/collider-test.md).
			state.CameraAngles = hero.CameraAngles;
			state.Stamina = stamina.CurrentValue;
			state.StaminaMax = stamina.MaxValue;
			state.Health = hero.Health;
			state.HealthMax = hero.GetMaxHealth();
			state.HullHeight = hero.Collision.Maxs.Z - hero.Collision.Mins.Z;
			state.EntityFlags = (uint)hero.Flags;
			if (state.HullHeight != _lastHull || state.EntityFlags != _lastEntityFlags)
			{
				// Learning what slides and crouches look like from here (no flag for either in Deadworks).
				Log($"hull {state.HullHeight:F1} entity flags 0x{state.EntityFlags:X} eye {hero.EyePosition.Z - hero.Position.Z:F0} speed {new Vector2(hero.AbsVelocity.X, hero.AbsVelocity.Y).Length():F0}");
				_lastHull = state.HullHeight;
				_lastEntityFlags = state.EntityFlags;
			}
		}
		state.RecenterSerial = _recenterSerial;
		state.RecenterDelta = _recenterDelta;
		_mapping.WriteHeroState(state);
		SyncColliders();
	}

	// Rebuild colliders when the Minecraft client publishes a new cube set. While it isn't linked
	// (generation bumps with flags 0) the colliders stay, so the hero doesn't drop through the world.
	private void SyncColliders()
	{
		if (!_mapping!.TryReadMcState(_cubeGeneration, out var mc, _cubes) || mc.Generation == _cubeGeneration) return;
		_cubeGeneration = mc.Generation;
		bool linked = (mc.Flags & (uint)McFlags.Linked) != 0;
		if (linked != _clientLinked) Log(linked ? "Minecraft client linked" : "Minecraft client unlinked");
		if (linked && !_clientLinked) _colliders.ClientRelinked();
		if (linked != _clientLinked && Server.MapName == VoidMap && FindHero() is { } hero)
		{
			if (!linked && hero.IsAlive)
			{
				// The stale colliders no longer follow the world, so a hero left to wander can walk off them
				// and off the slab. Park it in the middle of the slab until the client links again.
				hero.Teleport(position: SlabCentreTop + new Vector3(0f, 0f, 16f), velocity: Vector3.Zero);
				Log("parked the hero on the slab while Minecraft is unlinked");
			}
			else if (linked && !hero.IsAlive)
			{
				hero.ForceRespawn(true);  // don't make the player wait out a death from while they were away
				Log("respawned the hero for the relinked client");
			}
		}
		_clientLinked = linked;
		if (!linked) return;
		Log(_colliders.Apply(mc, _cubes));
		if (ColliderPool.HideProblem is { } problem && !_reportedHideProblem)
		{
			_reportedHideProblem = true;
			Log($"couldn't hide colliders: {problem}");
		}
	}

	// Only once colliders exist: before the Minecraft client links, the hero stands on the map's own
	// floor and must stay there.
	private void MaybeRecenter(CCitadelPlayerPawn hero)
	{
		// Not before home is known, and not until the hero has been alive a moment: during hero
		// select and spawning the pawn sits at the origin and must not be moved.
		// Never while the client is unlinked: the stale colliders don't follow the hero, so a hero that
		// wandered off them would be lifted into empty air, fall and be lifted again, forever.
		if (!_clientLinked || _colliders.Active == 0 || Server.MapName != VoidMap || _aliveSince < 0 || GlobalVars.CurTime - _aliveSince < 1f) return;
		var p = hero.Position;
		if (Math.Abs(p.X - Home.X) < MaxHorizontal && Math.Abs(p.Y - Home.Y) < MaxHorizontal
			&& Math.Abs(p.Z - Home.Z) < MaxVertical) return;
		var delta = Home - p;
		hero.Teleport(position: p + delta);  // keeps velocity: a fall or a dash carries on
		_colliders.Shift(delta);
		_recenterSerial++;
		_recenterDelta = delta;
		Log($"recentred by {delta} (hero was at {p}, now {hero.Position}) tick {GlobalVars.TickCount}");
	}

	// The human player's hero, re-found when it changes (respawn, hero swap, reconnect).
	private CCitadelPlayerPawn? FindHero()
	{
		if (_hero != null && _hero.IsValid && !_hero.IsBot) return _hero;
		_hero = null;
		int humans = 0;
		foreach (var pawn in Entities.ByClass<CCitadelPlayerPawn>())
		{
			if (pawn.IsBot || pawn.Controller == null) continue;
			humans++;
			_hero ??= pawn;
		}
		if (humans > 1 && !_warnedSeveralHumans)
		{
			_warnedSeveralHumans = true;
			Log($"{humans} human players on this server; bridging only the first. Each player needs their own server.");
		}
		return _hero;
	}

	[GameEventHandler("player_used_ability")]
	public HookResult OnPlayerUsedAbility(PlayerUsedAbilityEvent args)
	{
		if (_mapping != null && _hero != null && args.Player is { } pawn && pawn.EntityHandle == _hero.EntityHandle)
			_mapping.AppendAbilityEvent(AbilityEventKind.Used, (ulong)GlobalVars.TickCount, args.Abilityname);
		return HookResult.Continue;
	}

	// Also appended to %TEMP%\deadcraft-plugin.log: the server console can't be captured.
	private static readonly string LogPath = Path.Combine(Path.GetTempPath(), "deadcraft-plugin.log");

	private static void Log(string text)
	{
		string line = $"[deadcraft] {text}";
		Console.WriteLine(line);
		try { File.AppendAllText(LogPath, $"{DateTime.Now:HH:mm:ss} {line}{Environment.NewLine}"); } catch (IOException) { }
	}
}
