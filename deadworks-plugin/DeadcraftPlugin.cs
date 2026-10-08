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

	public override void OnUnload()
	{
		if (_mapping == null) return;
		_mapping.WriteHeroState(new HeroState());  // flags 0: no hero
		_mapping.Dispose();
		_mapping = null;
	}

	public override void OnGameFrame(bool simulating, bool firstTick, bool lastTick)
	{
		if (_mapping == null) return;
		_mapping.DeadlockHeartbeat();
		var hero = FindHero();
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
		_mapping.WriteHeroState(state);
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

	private static void Log(string text) => Console.WriteLine($"[deadcraft] {text}");
}
