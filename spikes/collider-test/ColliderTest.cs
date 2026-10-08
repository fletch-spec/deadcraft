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
		foreach (var model in FloorModels.Values) Precache.AddResource(model);
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
		Say(caller, $"view={Fmt(pawn.ViewAngles)} eyeAng={Fmt(pawn.EyeAngles)} camAng={Fmt(pawn.CameraAngles)}");
		Say(caller, $"stamina={pawn.GetStamina():F2} hp={pawn.Health}/{pawn.GetMaxHealth()}");
	}

	// mode: "vphys" uses the model's own collision mesh; "bbox" switches to an axis-aligned box
	// from the model's bounds (SetSolid only applies when SetModel rebuilds the body).
	// Resizing Collision.Mins/Maxs after spawn crashes the server (coreclr access violation, v0.5.6),
	// so merged colliders are tried through the ModelScale keyvalue instead: scale 2 = a 2x2x2 block box.
	[Command("dc_box", Description = "dc_box [vphys|bbox] [crate|cube] [scale]: spawn a box 200 units ahead")]
	public void CmdBox(CCitadelPlayerController caller, string mode = "vphys", string model = "crate", float scale = 1f)
	{
		if (caller.GetHeroPawn() is not { } pawn) { Say(caller, "no hero pawn"); return; }
		string path = model == "cube" ? CubeModel : CrateModel;
		var pos = pawn.Position + Forward(pawn.CameraAngles) * 200f;
		var box = SpawnBox(path, pos, mode == "vphys", scale);
		if (box == null) { Say(caller, "spawn failed"); return; }
		if (mode == "bbox")
		{
			box.Collision?.SetSolid(SolidType.BBox);
			box.SetModel(path == CrateModel ? CubeModel : CrateModel);
			box.SetModel(path);
		}
		var col = box.Collision;
		Say(caller, $"box #{_boxes.Count} {mode} {model} x{scale} at {Fmt(pos)} mins={Fmt(col?.Mins)} maxs={Fmt(col?.Maxs)} " +
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

	// move=false spawns the grid but never teleports it: separates "how many colliders" from
	// "how many collider updates per tick" (1024 moving boxes flooded the client's netchan).
	[Command("dc_pool", Description = "dc_pool <n> [move]: spawn n boxes in a grid; move=true teleports all of them every tick")]
	public void CmdPool(CCitadelPlayerController caller, int n = 256, bool move = true)
	{
		if (caller.GetHeroPawn() is not { } pawn) { Say(caller, "no hero pawn"); return; }
		int side = (int)MathF.Ceiling(MathF.Sqrt(n));
		var origin = pawn.Position + Forward(pawn.CameraAngles) * 300f;
		for (int i = 0; i < n; i++)
		{
			var pos = origin + new Vector3(i % side * 80f, i / side * 80f, 0f);
			if (SpawnBox(CrateModel, pos, true) != null) _poolHome.Add(pos);
		}
		_pooling = move;
		_poolMs = 0; _poolTicks = 0; _lastReport = GlobalVars.CurTime;
		Say(caller, move ? $"pool of {_poolHome.Count} moving; cost is logged every 5 s" : $"pool of {_poolHome.Count} spawned, static");
	}

	// For the feel test: a custom map may not spawn the hero where the course is.
	[Command("dc_tp", Description = "dc_tp <x> <y> <z>: teleport your hero (Source units)")]
	public void CmdTeleport(CCitadelPlayerController caller, float x, float y, float z)
	{
		if (caller.GetHeroPawn() is not { } pawn) { Say(caller, "no hero pawn"); return; }
		pawn.TeleportWithView(new Vector3(x, y, z), pawn.CameraAngles);
		Say(caller, $"teleported to {Fmt(pawn.Position)}");
	}

	// ---- Feel test course (docs/feel-test.md) ----------------------------------------------------
	// Units per Minecraft block, decided from the collider test. Moves to /protocol in M2.
	private const float UnitsPerBlock = 64f;
	// Course collider: Deadlock's test cube. Its collision is a clean box (the crate's follows its
	// planks, which left ridges on scaled floors). Origin at its centre; bounds from dc_floor:
	// mins (-39.5, -39.7, -39.4), maxs (39.9, 39.7, 39.6), so about 79.4 units per side.
	private const string BlockModel = "models/test/cube_test/citadel_center_cube_01.vmdl";
	private const float BlockModelSize = 79.4f;
	private const float BlockModelTop = 39.6f;
	// The superflat export has 4 floor layers (bedrock, dirt, dirt, grass) under the course.
	private const int CourseFloorBlocks = 4;
	private static readonly string CourseDir = Path.Combine(
		Path.GetDirectoryName(Environment.ProcessPath) ?? ".", "deadcraft", "courses");
	private Vector3? _courseAnchor;

	private sealed record Course(string Name, int[] SizeBlocks, float[][] Boxes);

	// Minecraft (x east, y up, z south, blocks) to Source (x, y left, z up, units), relative to the anchor.
	private static Vector3 ToSource(Vector3 anchor, float x, float y, float z) =>
		anchor + new Vector3(x * UnitsPerBlock, -z * UnitsPerBlock, y * UnitsPerBlock);

	[Command("dc_build", Description = "dc_build <course> [height]: build a converted .nbt course in the air above you")]
	public void CmdBuild(CCitadelPlayerController caller, string name = "feel1", float height = 400f)
	{
		if (caller.GetHeroPawn() is not { } pawn) { Say(caller, "no hero pawn"); return; }
		string file = Path.Combine(CourseDir, name + ".json");
		if (!File.Exists(file)) { Say(caller, $"no course at {file}"); return; }
		var course = System.Text.Json.JsonSerializer.Deserialize<Course>(File.ReadAllText(file),
			new System.Text.Json.JsonSerializerOptions { PropertyNameCaseInsensitive = true });
		if (course == null) { Say(caller, "course file is empty"); return; }

		ClearAll();
		float half = course.SizeBlocks[0] / 2f;
		var p = pawn.Position;
		var anchor = new Vector3(p.X - half * UnitsPerBlock, p.Y + half * UnitsPerBlock,
			p.Z + height - CourseFloorBlocks * UnitsPerBlock);
		int spawned = 0;
		foreach (var b in course.Boxes)
		{
			float x = b[0], y = b[1], z = b[2], s = b[3];
			// Scale so one side is s blocks, then put the box's top exactly at y + s.
			float scale = s * UnitsPerBlock / BlockModelSize;
			var top = ToSource(anchor, x + s / 2f, y + s, z + s / 2f);
			if (SpawnBox(BlockModel, top - new Vector3(0, 0, BlockModelTop * scale), true, scale) != null) spawned++;
		}
		_courseAnchor = anchor;
		Say(caller, $"built {course.Name}: {spawned}/{course.Boxes.Length} colliders, anchor {Fmt(anchor)}");
		TeleportToCourse(pawn);
	}

	// Seam test: a 3 x 3 floor of 4-block tiles, top 1 block above your feet, to see whether a model's
	// collision is a clean box (the scaled crate floor has ridges at its edges).
	private static readonly Dictionary<string, string> FloorModels = new()
	{
		["crate"] = CrateModel,
		["cube"] = CubeModel,
		["center"] = BlockModel,
	};

	[Command("dc_floor", Description = "dc_floor [crate|cube|center]: 3x3 floor of 4-block tiles 500 units ahead")]
	public void CmdFloor(CCitadelPlayerController caller, string model = "cube")
	{
		if (caller.GetHeroPawn() is not { } pawn) { Say(caller, "no hero pawn"); return; }
		if (!FloorModels.TryGetValue(model, out var path)) { Say(caller, "models: crate, cube, center"); return; }
		var probe = SpawnBox(path, pawn.Position + new Vector3(0, 0, -10000f), true);
		if (probe?.Collision is not { } pc) { Say(caller, "spawn failed"); return; }
		Vector3 mins = pc.Mins, maxs = pc.Maxs;
		float scale = 4f * UnitsPerBlock / (maxs.X - mins.X);
		var center = pawn.Position + Forward(pawn.CameraAngles) * 500f;
		float tile = 4f * UnitsPerBlock;
		for (int i = -1; i <= 1; i++)
			for (int j = -1; j <= 1; j++)
			{
				// Place so the bounds' top sits one block above the hero's feet.
				var origin = center + new Vector3(i * tile, j * tile, UnitsPerBlock - maxs.Z * scale);
				SpawnBox(path, origin, true, scale);
			}
		Say(caller, $"floor of {model}: bounds mins={Fmt(mins)} maxs={Fmt(maxs)}, scale {scale:F3}");
	}

	// The map's world entity (index 0) bounds tell us how high a course can go.
	[Command("dc_bounds", Description = "Print the map's world bounds")]
	public void CmdBounds(CCitadelPlayerController caller)
	{
		var world = CBaseEntity.FromIndex(0);
		var col = world?.Collision;
		Say(caller, $"world {world?.Classname}: mins={Fmt(col?.Mins)} maxs={Fmt(col?.Maxs)}");
		if (caller.GetHeroPawn() is { } pawn) Say(caller, $"you are at {Fmt(pawn.Position)}");
	}

	[Command("dc_course", Description = "Teleport back to the start of the built course")]
	public void CmdCourse(CCitadelPlayerController caller)
	{
		if (caller.GetHeroPawn() is not { } pawn) { Say(caller, "no hero pawn"); return; }
		if (_courseAnchor == null) { Say(caller, "build a course first: /dc_build feel1"); return; }
		TeleportToCourse(pawn);
	}

	// Start at the course's north-west corner, facing east (+x in Minecraft, yaw 0 in Source).
	private void TeleportToCourse(CCitadelPlayerPawn pawn)
	{
		var start = ToSource(_courseAnchor!.Value, 1.5f, CourseFloorBlocks, 1.5f) + new Vector3(0, 0, 8f);
		pawn.TeleportWithView(start, Vector3.Zero);
		Log($"teleported to course start {Fmt(start)}");
		_trackPawn = pawn;
		_trackUntil = GlobalVars.CurTime + 3f;
		_trackNext = 0f;
	}

	// After a course teleport, log where the hero actually is for 3 s, to catch the map moving it.
	private CCitadelPlayerPawn? _trackPawn;
	private float _trackUntil, _trackNext;

	private void TrackPawn(float t)
	{
		if (_trackPawn == null) return;
		if (t > _trackUntil || !_trackPawn.IsValid) { _trackPawn = null; return; }
		if (t < _trackNext) return;
		_trackNext = t + 0.25f;
		Log($"track t+{3f - (_trackUntil - t):F2}s pos={Fmt(_trackPawn.Position)} vel={Fmt(_trackPawn.AbsVelocity)} " +
			$"ground={_trackPawn.IsOnGround} alive={_trackPawn.IsAlive}");
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
		TrackPawn(t);

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

	private CBaseEntity? SpawnBox(string model, Vector3 pos, bool vphysics, float scale = 1f)
	{
		var box = CBaseEntity.CreateByDesignerName("prop_dynamic");
		if (box == null) return null;
		var ekv = new CEntityKeyValues();
		ekv.SetString("model", model);
		ekv.SetVector("origin", pos);
		if (vphysics) ekv.SetInt("solid", (int)SolidType.VPhysics);
		if (scale != 1f) ekv.SetFloat("ModelScale", scale);
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
		_courseAnchor = null;
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

	// Also appended to %TEMP%\deadcraft-collider-test.log so results can be read without the console window.
	private static readonly string LogPath = Path.Combine(Path.GetTempPath(), "deadcraft-collider-test.log");

	private static void Log(string text)
	{
		string line = $"[dc] {text}";
		Console.WriteLine(line);
		try { File.AppendAllText(LogPath, $"{DateTime.Now:HH:mm:ss} {line}{Environment.NewLine}"); } catch (IOException) { }
	}
}
