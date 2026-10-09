using System.Numerics;

namespace Deadcraft.HeroExport;

/// <summary>
/// Reduces a triangle list to about a target count by quadric-error edge collapses (Garland and
/// Heckbert), working on positions so texture seams simplify too.
///
/// <para>The source splits vertices where UVs or normals differ (seams), so one position can have
/// several vertices ("wedges"). Collapses are half-edge on positions: position u merges into its
/// neighbour v and keeps v's attributes. Each of u's wedges is replaced by the wedge of v it shares a
/// triangle with; if one has none, the edge crosses a seam and isn't collapsed, so both sides of a
/// seam always move together and textures stay put. The mesh's true border (a hair card's or a hem's
/// edge) slides only along itself: a plane through each border edge, perpendicular to its face,
/// joins the quadrics.</para>
/// </summary>
internal static class Simplifier
{
	private struct Quadric
	{
		public double A, B, C, D, E, F, G, H, I, J;  // symmetric 4x4: aa ab ac ad bb bc bd cc cd dd

		public static Quadric Plane(Vector3 n, float d, double w) => new()
		{
			A = w * n.X * n.X, B = w * n.X * n.Y, C = w * n.X * n.Z, D = w * n.X * d, E = w * n.Y * n.Y,
			F = w * n.Y * n.Z, G = w * n.Y * d, H = w * n.Z * n.Z, I = w * n.Z * d, J = w * d * d,
		};

		public void Add(in Quadric q)
		{
			A += q.A; B += q.B; C += q.C; D += q.D; E += q.E; F += q.F; G += q.G; H += q.H; I += q.I; J += q.J;
		}

		public readonly double Error(Vector3 p)
		{
			double x = p.X, y = p.Y, z = p.Z;
			return A * x * x + 2 * B * x * y + 2 * C * x * z + 2 * D * x + E * y * y + 2 * F * y * z + 2 * G * y + H * z * z + 2 * I * z + J;
		}
	}

	/// <summary>Returns the kept triangles' indices (into the same vertex array).</summary>
	public static int[] Simplify(Vector3[] positions, int[] triangles, int targetTriangles)
	{
		int nv = positions.Length, nt = triangles.Length / 3;
		if (nt <= targetTriangles) return triangles;
		var tri = (int[])triangles.Clone();

		// Position id ("point") for every vertex.
		var pointOf = new int[nv];
		var pointIds = new Dictionary<(int, int, int), int>();
		var points = new List<Vector3>();
		for (int v = 0; v < nv; v++)
		{
			var p = positions[v];
			var key = ((int)MathF.Round(p.X * 1e5f), (int)MathF.Round(p.Y * 1e5f), (int)MathF.Round(p.Z * 1e5f));
			if (!pointIds.TryGetValue(key, out int id))
			{
				pointIds[key] = id = points.Count;
				points.Add(p);
			}
			pointOf[v] = id;
		}
		int np = points.Count;
		var alive = new bool[nt];
		var pointTris = new List<int>[np];
		for (int i = 0; i < np; i++) pointTris[i] = [];
		for (int t = 0; t < nt; t++)
		{
			int a = pointOf[tri[t * 3]], b = pointOf[tri[t * 3 + 1]], c = pointOf[tri[t * 3 + 2]];
			if (a == b || b == c || a == c) continue;  // degenerate in the source
			alive[t] = true;
			pointTris[a].Add(t);
			pointTris[b].Add(t);
			pointTris[c].Add(t);
		}
		int remaining = alive.Count(x => x);

		var q = new Quadric[np];
		var edgeUse = new Dictionary<(int, int), int>();
		for (int t = 0; t < nt; t++)
		{
			if (!alive[t]) continue;
			int a = pointOf[tri[t * 3]], b = pointOf[tri[t * 3 + 1]], c = pointOf[tri[t * 3 + 2]];
			var n = Vector3.Cross(points[b] - points[a], points[c] - points[a]);
			float len = n.Length();
			if (len > 1e-12f)
			{
				n /= len;
				var plane = Quadric.Plane(n, -Vector3.Dot(n, points[a]), len / 2);
				q[a].Add(plane);
				q[b].Add(plane);
				q[c].Add(plane);
			}
			foreach (var (x, y) in new[] { (a, b), (b, c), (c, a) })
			{
				var key = x < y ? (x, y) : (y, x);
				edgeUse[key] = edgeUse.GetValueOrDefault(key) + 1;
			}
		}
		for (int t = 0; t < nt; t++)
		{
			if (!alive[t]) continue;
			for (int k = 0; k < 3; k++)
			{
				int a = pointOf[tri[t * 3 + k]], b = pointOf[tri[t * 3 + (k + 1) % 3]], c = pointOf[tri[t * 3 + (k + 2) % 3]];
				if (edgeUse[a < b ? (a, b) : (b, a)] != 1) continue;
				Vector3 pa = points[a], pb = points[b];
				var n = Vector3.Cross(pb - pa, Vector3.Cross(pb - pa, points[c] - pa));
				float len = n.Length();
				if (len < 1e-12f) continue;
				n /= len;
				var border = Quadric.Plane(n, -Vector3.Dot(n, pa), 1000 * (pb - pa).LengthSquared());
				q[a].Add(border);
				q[b].Add(border);
			}
		}

		var stamp = new int[np];
		var heap = new PriorityQueue<(int U, int V, int Su, int Sv), double>();
		void Push(int u, int v)
		{
			if (u == v) return;
			var qq = q[u];
			qq.Add(q[v]);
			heap.Enqueue((u, v, stamp[u], stamp[v]), qq.Error(points[v]));
		}
		foreach (var (a, b) in edgeUse.Keys)
		{
			Push(a, b);
			Push(b, a);
		}

		var removed = new bool[np];
		var wedgeMap = new Dictionary<int, int>();
		while (remaining > targetTriangles && heap.TryDequeue(out var e, out _))
		{
			if (removed[e.U] || removed[e.V] || stamp[e.U] != e.Su || stamp[e.V] != e.Sv) continue;
			if (!CanCollapse(e.U, e.V)) continue;
			foreach (int t in pointTris[e.U])
			{
				if (!alive[t]) continue;
				int i0 = t * 3;
				if (pointOf[tri[i0]] == e.V || pointOf[tri[i0 + 1]] == e.V || pointOf[tri[i0 + 2]] == e.V)
				{
					alive[t] = false;
					remaining--;
					continue;
				}
				for (int k = 0; k < 3; k++)
					if (pointOf[tri[i0 + k]] == e.U) tri[i0 + k] = wedgeMap[tri[i0 + k]];
				pointTris[e.V].Add(t);
			}
			removed[e.U] = true;
			q[e.V].Add(q[e.U]);
			stamp[e.V]++;
			var neighbours = new HashSet<int>();
			foreach (int t in pointTris[e.V])
				if (alive[t])
					for (int k = 0; k < 3; k++) neighbours.Add(pointOf[tri[t * 3 + k]]);
			neighbours.Remove(e.V);
			foreach (int n in neighbours)
			{
				stamp[n]++;
				foreach (int t in pointTris[n])
					if (alive[t])
						for (int k = 0; k < 3; k++)
						{
							int o = pointOf[tri[t * 3 + k]];
							if (o != n)
							{
								Push(n, o);
								Push(o, n);
							}
						}
			}
		}

		var kept = new List<int>(remaining * 3);
		for (int t = 0; t < nt; t++)
			if (alive[t]) kept.AddRange([tri[t * 3], tri[t * 3 + 1], tri[t * 3 + 2]]);
		return [.. kept];

		// Fills wedgeMap (u's wedges -> v's wedges) and checks that no surviving triangle flips.
		bool CanCollapse(int u, int v)
		{
			wedgeMap.Clear();
			foreach (int t in pointTris[u])
			{
				if (!alive[t]) continue;
				int i0 = t * 3, wu = -1, wv = -1;
				for (int k = 0; k < 3; k++)
				{
					int p = pointOf[tri[i0 + k]];
					if (p == u) wu = tri[i0 + k];
					else if (p == v) wv = tri[i0 + k];
				}
				if (wv >= 0)
				{
					if (wedgeMap.TryGetValue(wu, out int prior) && prior != wv) return false;
					wedgeMap[wu] = wv;
				}
			}
			foreach (int t in pointTris[u])
			{
				if (!alive[t]) continue;
				int i0 = t * 3;
				bool hasV = false;
				for (int k = 0; k < 3; k++)
				{
					int w = tri[i0 + k];
					if (pointOf[w] == v) hasV = true;
					else if (pointOf[w] == u && !wedgeMap.ContainsKey(w)) return false;  // crosses a seam
				}
				if (hasV) continue;
				Vector3 a = points[pointOf[tri[i0]]], b = points[pointOf[tri[i0 + 1]]], c = points[pointOf[tri[i0 + 2]]];
				var before = Vector3.Cross(b - a, c - a);
				Vector3 a2 = pointOf[tri[i0]] == u ? points[v] : a;
				Vector3 b2 = pointOf[tri[i0 + 1]] == u ? points[v] : b;
				Vector3 c2 = pointOf[tri[i0 + 2]] == u ? points[v] : c;
				var after = Vector3.Cross(b2 - a2, c2 - a2);
				float lb = before.Length(), la = after.Length();
				if (la < 1e-12f || lb < 1e-12f) return false;
				if (Vector3.Dot(before, after) / (lb * la) < 0.3f) return false;
			}
			return true;
		}
	}
}
