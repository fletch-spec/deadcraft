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

	// Recentring keeps the hero inside this box around Home: above the void map's floor block (top
	// at z 512) and inside the map's bounds, where colliders work. At z 6000 they didn't (the hero
	// fell through them); the feel-test course worked up to about z 2500. Falling below MinZ recentres
	// again, so caves and ravines have no depth limit; long walks never reach the map's edges.
	// Home's x and y are found at map start (FindHome): the centre of the void map's floor slab.
	// Entities far from the map's geometry aren't sent to clients, so home must sit over the slab.
	private Vector3 Home = new(0f, 0f, 2000f);
	private bool _homeFound;
	private const float MaxHorizontal = 3000f;
	private const string VoidMap = "deadcraft_void";
	private CCitadelPlayerPawn? _hero;
	private bool _warnedSeveralHumans;

	public override void OnLoad(bool isReload)
	{
		try
		{
			_mapping = Mapping.OpenOrCreate();
			Log($"bridge open: {Proto.MappingName}, protocol v{Proto.Version}");
		}
		catch (Exception e)
		{
			Log($"bridge disabled: {e.Message}");
		}
	}

	public override void OnPrecacheResources() => Precache.AddResource(ColliderPool.Model);

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
		if (_mapping == null) return;
		_mapping.DeadlockHeartbeat();
		if (Server.MapName != _cheatsMap)
		{
			// The void map has no baked light; sv_cheats lets players use mat_fullbright 1.
			_cheatsMap = Server.MapName;
			if (_cheatsMap == VoidMap) Server.ExecuteCommand("sv_cheats 1");
		}
		if (!_homeFound) FindHome();
		var hero = FindHero();
		if (hero != null && hero.IsAlive) MaybeRecenter(hero);
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
		if ((mc.Flags & (uint)McFlags.Linked) == 0) return;
		Log(_colliders.Apply(mc, _cubes));
	}

	// The void map's floor slab is 8192 units square with a corner at the origin (maps/deadcraft_void.vmap);
	// which quadrant isn't recorded, so trace down at each candidate centre and take the one that hits.
	// Runs before any collider exists, so only the map's own geometry can be hit.
	private void FindHome()
	{
		_homeFound = true;
		if (Server.MapName != VoidMap || _colliders.Total > 0) return;
		foreach (var (x, y) in new[] { (4096f, 4096f), (4096f, -4096f), (-4096f, 4096f), (-4096f, -4096f) })
		{
			var hit = Trace.Ray(new Vector3(x, y, 4000f), new Vector3(x, y, -4000f), InteractionLayer.Solid);
			Log($"floor probe at ({x}, {y}): {(hit.DidHit ? $"hit at z {hit.HitPosition.Z}" : "no hit")}");
			if (!hit.DidHit) continue;
			Home = new Vector3(x, y, hit.HitPosition.Z + 1500f);
			Log($"home is {Home}");
			return;
		}
		Log("no floor found under any candidate; home stays at " + Home);
	}

	// Only once colliders exist: before the Minecraft client links, the hero stands on the map's own
	// floor and must stay there.
	private void MaybeRecenter(CCitadelPlayerPawn hero)
	{
		if (_colliders.Active == 0) return;
		var p = hero.Position;
		if (Math.Abs(p.X - Home.X) < MaxHorizontal && Math.Abs(p.Y - Home.Y) < MaxHorizontal
			&& p.Z > Home.Z - 700f && p.Z < Home.Z + 700f) return;
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

	// What the Deadlock client is sent. It needs the colliders near its hero to predict movement
	// (without them it predicts falls and walks through walls), and nothing else: the server
	// collides with all of them anyway. Sending every collider crashed the server at about 1700, and
	// the void map's own visibility data only covers a thin layer over its floor, so set the
	// transmit bits ourselves: on within TransmitRadius of the hero, off for every other collider.
	// CheckTransmitEvent only offers Hide; it wraps the engine's per-player bitset, so we set bits
	// in it directly.
	private const float TransmitRadius = 8 * Proto.UnitsPerBlock;
	private static readonly System.Reflection.FieldInfo? TransmitBitsField =
		typeof(CheckTransmitEvent).GetField("_transmitBits", System.Reflection.BindingFlags.Instance | System.Reflection.BindingFlags.NonPublic);
	private bool _warnedTransmit;

	public override unsafe void OnCheckTransmit(CheckTransmitEvent args)
	{
		if (_colliders.Total == 0) return;
		foreach (var entity in _colliders.AllEntities)
			if (entity.IsValid) args.Hide(entity);
		if (_hero == null || !_hero.IsValid) return;
		if (TransmitBitsField?.GetValue(args) is not { } boxed)
		{
			if (!_warnedTransmit) Log("can't reach the transmit bitset; colliders may not show on clients");
			_warnedTransmit = true;
			return;
		}
		var bits = (ulong*)System.Reflection.Pointer.Unbox(boxed);
		foreach (var entity in _colliders.Near(_hero.Position, TransmitRadius))
		{
			int index = entity.EntityIndex;
			if (index >= 0) bits[index >> 6] |= 1UL << (index & 63);
		}
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
