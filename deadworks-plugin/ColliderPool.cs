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
	// Our own safety cap, not Deadlock's: raise it as stress tests show what Deadlock handles.
	private const int MaxColliders = 8000;

	private readonly record struct Key(int X, int Y, int Z, byte Edge);

	private readonly Dictionary<Key, CBaseEntity> _active = new();
	private readonly Dictionary<byte, Stack<CBaseEntity>> _parked = new();
	// Server tick each collider was spawned on: forcing a just-spawned entity onto a client crashes
	// the engine (null network state in engine2.dll), so Near() skips young ones.
	private readonly Dictionary<uint, int> _spawnedAt = new();
	private const int MinTransmitAgeTicks = 4;
	private int _total;
	private Double3 _frameOffset;
	// After a recentre the plugin's offset leads the client's until the client's next publish
	// catches up; cube sets that still carry the old offset are placed with ours.
	private bool _awaitingClientOffset;

	public int Active => _active.Count;
	public int Total => _total;

	/// <summary>Brings the colliders in line with a new cube set. Returns a one-line summary.</summary>
	public string Apply(McState state, List<Cube> cubes)
	{
		var incoming = state.FrameOffset;
		if (_awaitingClientOffset)
		{
			if (Close(incoming, _frameOffset)) _awaitingClientOffset = false;
			else incoming = _frameOffset;
		}
		bool offsetChanged = !Close(incoming, _frameOffset);
		_frameOffset = incoming;

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
				_spawnedAt[spawned.EntityHandle] = GlobalVars.TickCount;
			}
			if (entity == null) { dropped++; continue; }
			_active[key] = entity;
			added++;
		}
		return $"gen {state.Generation}: {cubes.Count} cubes, +{added} -{parked} moved {moved}" +
			(dropped > 0 ? $" DROPPED {dropped} (cap {MaxColliders})" : "") + $", {_active.Count} active / {_total} spawned";
	}

	/// <summary>
	/// Moves every active collider by <paramref name="delta"/> Source units, along with the frame
	/// offset, in the same tick the hero is teleported by the same amount (a recentre).
	/// </summary>
	public void Shift(Vector3 delta)
	{
		// Source (x, y, z) to Minecraft blocks: (x, z, -y) / UnitsPerBlock.
		_frameOffset = new Double3(_frameOffset.X + delta.X / Proto.UnitsPerBlock,
			_frameOffset.Y + delta.Z / Proto.UnitsPerBlock, _frameOffset.Z - delta.Y / Proto.UnitsPerBlock);
		foreach (var (key, entity) in _active)
			if (entity.IsValid) entity.Teleport(position: Place(key));
		_awaitingClientOffset = true;
	}

	private static bool Close(Double3 a, Double3 b) =>
		Math.Abs(a.X - b.X) < 1e-3 && Math.Abs(a.Y - b.Y) < 1e-3 && Math.Abs(a.Z - b.Z) < 1e-3;

	/// <summary>Every collider spawned so far, active or parked.</summary>
	public IEnumerable<CBaseEntity> AllEntities => _active.Values.Concat(_parked.Values.SelectMany(p => p));

	/// <summary>
	/// The active colliders within <paramref name="radius"/> Source units of <paramref name="point"/>
	/// (measured to the nearest point of each cube, so big cubes count when their surface is near).
	/// </summary>
	public IEnumerable<CBaseEntity> Near(Vector3 point, float radius)
	{
		double r2 = (double)radius * radius;
		int oldEnough = GlobalVars.TickCount - MinTransmitAgeTicks;
		foreach (var (key, entity) in _active)
		{
			if (!entity.IsValid || (_spawnedAt.TryGetValue(entity.EntityHandle, out int born) && born > oldEnough)) continue;
			// The cube in Minecraft blocks (hero frame), then the point's distance to it in Source units.
			double edge = key.Edge / 2.0;
			double x0 = key.X / 2.0 + _frameOffset.X, y0 = key.Y / 2.0 + _frameOffset.Y, z0 = key.Z / 2.0 + _frameOffset.Z;
			// Source (x, y, z) = (mc x, -mc z, mc y) * UnitsPerBlock.
			double px = point.X / Proto.UnitsPerBlock, py = point.Z / Proto.UnitsPerBlock, pz = -point.Y / Proto.UnitsPerBlock;
			double dx = Math.Max(0, Math.Max(x0 - px, px - (x0 + edge)));
			double dy = Math.Max(0, Math.Max(y0 - py, py - (y0 + edge)));
			double dz = Math.Max(0, Math.Max(z0 - pz, pz - (z0 + edge)));
			if ((dx * dx + dy * dy + dz * dz) * Proto.UnitsPerBlock * Proto.UnitsPerBlock <= r2) yield return entity;
		}
	}

	/// <summary>Removes every collider (plugin unload).</summary>
	public void Clear()
	{
		foreach (var entity in _active.Values.Concat(_parked.Values.SelectMany(s => s)))
			if (entity.IsValid) entity.Remove();
		_active.Clear();
		_parked.Clear();
		_spawnedAt.Clear();
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
