using System.Numerics;
using Deadcraft.Protocol;
using DeadworksManaged.Api;

namespace Deadcraft.Plugin;

// Keeps Deadlock colliders matching the cube set the Minecraft client publishes (McState + Cubes).
//
// One collider per cube: a prop_dynamic of Deadlock's test cube with model collision, scaled to the
// cube's edge. Scale is fixed at spawn, so colliders are pooled per edge size. On a new generation
// only cubes that appeared or disappeared change: new ones take a parked collider of their size (or
// spawn one), gone ones are parked out of the way. Colliders are never deleted mid-game: the
// collider test showed spawning is fine but per-tick churn floods the client.
internal sealed class ColliderPool
{
	// Deadlock's test cube: its collision is a clean box (docs/collider-test.md). Origin at its centre.
	public const string Model = "models/test/cube_test/citadel_center_cube_01.vmdl";
	private const float ModelSize = 79.4f;
	private const float ModelTop = 39.6f;
	private static readonly Vector3 ParkAt = new(0f, 0f, -12000f);
	private const int MaxColliders = 2600;

	private readonly record struct Key(int X, int Y, int Z, byte Edge);

	private readonly Dictionary<Key, CBaseEntity> _active = new();
	private readonly Dictionary<byte, Stack<CBaseEntity>> _parked = new();
	private int _total;
	private Double3 _frameOffset;

	public int Active => _active.Count;
	public int Total => _total;

	/// <summary>Brings the colliders in line with a new cube set. Returns a one-line summary.</summary>
	public string Apply(McState state, List<Cube> cubes)
	{
		bool offsetChanged = state.FrameOffset != _frameOffset;
		_frameOffset = state.FrameOffset;

		var wanted = new HashSet<Key>(cubes.Count);
		foreach (var c in cubes)
			wanted.Add(new Key(state.Base.X * 2 + c.X, state.Base.Y * 2 + c.Y, state.Base.Z * 2 + c.Z, c.Edge));

		int parked = 0, moved = 0, added = 0, dropped = 0;
		foreach (var (key, entity) in _active.ToList())
		{
			if (wanted.Contains(key))
			{
				if (offsetChanged) { entity.Teleport(position: Place(key)); moved++; }
				continue;
			}
			_active.Remove(key);
			entity.Teleport(position: ParkAt);
			Parked(key.Edge).Push(entity);
			parked++;
		}
		foreach (var key in wanted)
		{
			if (_active.ContainsKey(key)) continue;
			var stack = Parked(key.Edge);
			CBaseEntity? entity = null;
			while (stack.Count > 0 && entity == null)
			{
				var candidate = stack.Pop();
				if (candidate.IsValid) entity = candidate; else _total--;
			}
			if (entity != null)
			{
				entity.Teleport(position: Place(key));
			}
			else if (_total < MaxColliders && Spawn(key) is { } spawned)
			{
				entity = spawned;
				_total++;
			}
			if (entity == null) { dropped++; continue; }
			_active[key] = entity;
			added++;
		}
		return $"gen {state.Generation}: {cubes.Count} cubes, +{added} -{parked} moved {moved}" +
			(dropped > 0 ? $" DROPPED {dropped} (cap {MaxColliders})" : "") + $", {_active.Count} active / {_total} spawned";
	}

	/// <summary>Removes every collider (plugin unload).</summary>
	public void Clear()
	{
		foreach (var entity in _active.Values.Concat(_parked.Values.SelectMany(s => s)))
			if (entity.IsValid) entity.Remove();
		_active.Clear();
		_parked.Clear();
		_total = 0;
	}

	private Stack<CBaseEntity> Parked(byte edge) =>
		_parked.TryGetValue(edge, out var s) ? s : _parked[edge] = new Stack<CBaseEntity>();

	// Where the test cube's origin goes so the collider fills the cube exactly: top face at the cube's
	// top, centred on its footprint. All in doubles until the final Source position.
	private Vector3 Place(Key key)
	{
		double edge = key.Edge / 2.0;
		double x = key.X / 2.0 + _frameOffset.X + edge / 2;
		double top = key.Y / 2.0 + _frameOffset.Y + edge;
		double z = key.Z / 2.0 + _frameOffset.Z + edge / 2;
		float scale = Scale(key.Edge);
		// Minecraft blocks to Source: (x, z, y) -> (x, -z, y) * UnitsPerBlock.
		return new Vector3((float)(x * Proto.UnitsPerBlock), (float)(-z * Proto.UnitsPerBlock),
			(float)(top * Proto.UnitsPerBlock) - ModelTop * scale);
	}

	private static float Scale(byte edge) => edge / 2f * Proto.UnitsPerBlock / ModelSize;

	private CBaseEntity? Spawn(Key key)
	{
		var entity = CBaseEntity.CreateByDesignerName("prop_dynamic");
		if (entity == null) return null;
		var ekv = new CEntityKeyValues();
		ekv.SetString("model", Model);
		ekv.SetVector("origin", Place(key));
		ekv.SetInt("solid", (int)SolidType.VPhysics);
		ekv.SetFloat("ModelScale", Scale(key.Edge));
		entity.Spawn(ekv);
		return entity;
	}
}
