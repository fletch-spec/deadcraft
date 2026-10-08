using System.Diagnostics;
using System.Numerics;
using DeadworksManaged.Api;

namespace ColliderTest;

// Answers one question before M4 is built: can a plugin-spawned box block the hero, carry it,
// and be moved every tick? Every command logs to the server console as "[dc] ..." so the
// output can be pasted back. See docs/collider-test.md.
public class ColliderTest : DeadworksPluginBase
{
	public override string Name => "Deadcraft Collider Test";

	// Stock Deadlock models, referenced by path only. Never copied into the repo.
	private const string CrateModel = "models/props_industrial/wood_crate_64.vmdl";
	private const string CubeModel = "models/props_gameplay/cube_100_preview.vmdl";

	private readonly List<CBaseEntity> _boxes = new();
	private readonly List<Vector3> _poolHome = new();
	private CBaseEntity? _mover;
	private Vector3 _moverHome;
	private bool _pooling;
	private readonly Stopwatch _poolWatch = new();
	private double _poolMs;
	private int _poolTicks;
	private float _lastReport;

	public override void OnPrecacheResources()
	{
		Precache.AddResource(CrateModel);
		Precache.AddResource(CubeModel);
	}

	public override void OnUnload() => ClearAll();

	[Command("dc_info", Description = "Print hero size, eye height and tick rate")]
	public void CmdInfo(CCitadelPlayerController caller)
	{
		if (caller.GetHeroPawn() is not { } pawn) { Say(caller, "no hero pawn"); return; }
		var col = pawn.Collision;
		var eye = pawn.EyePosition - pawn.Position;
		float tick = GlobalVars.IntervalPerTick;
		Say(caller, $"map={Server.MapName} hero={pawn.HeroID} tick={tick:F5}s ({(tick > 0 ? 1f / tick : 0):F1} Hz)");
		Say(caller, $"pos={Fmt(pawn.Position)} eyeOffset={Fmt(eye)} vel={Fmt(pawn.AbsVelocity)} ground={pawn.IsOnGround}");
		Say(caller, $"hull mins={Fmt(col?.Mins)} maxs={Fmt(col?.Maxs)} solid={col?.SolidType}");
		Say(caller, $"view={Fmt(pawn.ViewAngles)} stamina={pawn.GetStamina():F2} hp={pawn.Health}/{pawn.GetMaxHealth()}");
	}

	// mode: "vphys" uses the model's own collision mesh; "bbox" switches to an axis-aligned box
	// from the model's bounds (SetSolid only applies when SetModel rebuilds the body).
	[Command("dc_box", Description = "dc_box [vphys|bbox] [crate|cube]: spawn a box 200 units ahead")]
	public void CmdBox(CCitadelPlayerController caller, string mode = "vphys", string model = "crate")
	{
		if (caller.GetHeroPawn() is not { } pawn) { Say(caller, "no hero pawn"); return; }
		string path = model == "cube" ? CubeModel : CrateModel;
		var pos = pawn.Position + Forward(pawn.ViewAngles) * 200f;
		var box = SpawnBox(path, pos, mode == "vphys");
		if (box == null) { Say(caller, "spawn failed"); return; }
		if (mode == "bbox")
		{
			box.Collision?.SetSolid(SolidType.BBox);
			box.SetModel(path == CrateModel ? CubeModel : CrateModel);
			box.SetModel(path);
		}
		var col = box.Collision;
		Say(caller, $"box #{_boxes.Count} {mode} {model} at {Fmt(pos)} mins={Fmt(col?.Mins)} maxs={Fmt(col?.Maxs)} " +
			$"solid={col?.SolidType} as={col?.InteractsAs} with={col?.InteractsWith} group={col?.CollisionGroup}");
	}

	[Command("dc_move", Description = "Toggle: the last box bobs 64 units up and down every tick")]
	public void CmdMove(CCitadelPlayerController caller)
	{
		if (_mover != null) { _mover = null; Say(caller, "move off"); return; }
		if (_boxes.Count == 0 || !_boxes[^1].IsValid) { Say(caller, "spawn a box first"); return; }
		_mover = _boxes[^1];
		_moverHome = _mover.Position;
		Say(caller, "move on: stand on the box and see if it carries you");
	}

	[Command("dc_pool", Description = "dc_pool <n>: spawn n boxes in a grid and teleport all of them every tick")]
	public void CmdPool(CCitadelPlayerController caller, int n = 256)
	{
		if (caller.GetHeroPawn() is not { } pawn) { Say(caller, "no hero pawn"); return; }
		int side = (int)MathF.Ceiling(MathF.Sqrt(n));
		var origin = pawn.Position + Forward(pawn.ViewAngles) * 300f;
		for (int i = 0; i < n; i++)
		{
			var pos = origin + new Vector3(i % side * 80f, i / side * 80f, 0f);
			if (SpawnBox(CrateModel, pos, true) != null) _poolHome.Add(pos);
		}
		_pooling = true;
		_poolMs = 0; _poolTicks = 0; _lastReport = GlobalVars.CurTime;
		Say(caller, $"pool of {_poolHome.Count} running; cost is logged every 5 s");
	}

	// For the feel test: a custom map may not spawn the hero where the course is.
	[Command("dc_tp", Description = "dc_tp <x> <y> <z>: teleport your hero (Source units)")]
	public void CmdTeleport(CCitadelPlayerController caller, float x, float y, float z)
	{
		if (caller.GetHeroPawn() is not { } pawn) { Say(caller, "no hero pawn"); return; }
		pawn.TeleportWithView(new Vector3(x, y, z), pawn.CameraAngles);
		Say(caller, $"teleported to {Fmt(pawn.Position)}");
	}

	[Command("dc_clear", Description = "Remove every test box")]
	public void CmdClear(CCitadelPlayerController caller)
	{
		int n = _boxes.Count;
		ClearAll();
		Say(caller, $"removed {n} boxes");
	}

	public override void OnGameFrame(bool simulating, bool firstTick, bool lastTick)
	{
		if (!simulating) return;
		float t = GlobalVars.CurTime;

		if (_mover != null)
		{
			if (!_mover.IsValid) _mover = null;
			else _mover.Teleport(position: _moverHome + new Vector3(0, 0, 32f + 32f * MathF.Sin(t * MathF.PI / 2f)));
		}

		if (_pooling)
		{
			// Alternate between two spots a hair apart so every teleport is a real move.
			float jitter = (GlobalVars.TickCount & 1) * 0.5f;
			_poolWatch.Restart();
			int poolStart = _boxes.Count - _poolHome.Count;
			for (int i = 0; i < _poolHome.Count; i++)
			{
				var box = _boxes[poolStart + i];
				if (box.IsValid) box.Teleport(position: _poolHome[i] + new Vector3(0, 0, jitter));
			}
			_poolMs += _poolWatch.Elapsed.TotalMilliseconds;
			_poolTicks++;
			if (t - _lastReport >= 5f)
			{
				Log($"pool n={_poolHome.Count} teleport cost {(_poolMs / _poolTicks):F3} ms/tick over {_poolTicks} ticks " +
					$"(tick budget {GlobalVars.IntervalPerTick * 1000f:F2} ms)");
				_poolMs = 0; _poolTicks = 0; _lastReport = t;
			}
		}
	}

	private CBaseEntity? SpawnBox(string model, Vector3 pos, bool vphysics)
	{
		var box = CBaseEntity.CreateByDesignerName("prop_dynamic");
		if (box == null) return null;
		var ekv = new CEntityKeyValues();
		ekv.SetString("model", model);
		ekv.SetVector("origin", pos);
		if (vphysics) ekv.SetInt("solid", (int)SolidType.VPhysics);
		box.Spawn(ekv);
		_boxes.Add(box);
		return box;
	}

	private void ClearAll()
	{
		_mover = null;
		_pooling = false;
		foreach (var box in _boxes)
			if (box.IsValid) box.Remove();
		_boxes.Clear();
		_poolHome.Clear();
	}

	private static Vector3 Forward(Vector3 angles)
	{
		float yaw = angles.Y * MathF.PI / 180f;
		return new Vector3(MathF.Cos(yaw), MathF.Sin(yaw), 0f);
	}

	private static string Fmt(Vector3? v) => v is { } x ? $"({x.X:F1}, {x.Y:F1}, {x.Z:F1})" : "null";

	private static void Say(CCitadelPlayerController caller, string text)
	{
		Chat.PrintToChat(caller, text);
		Log(text);
	}

	private static void Log(string text) => Console.WriteLine($"[dc] {text}");
}
