using System.Numerics;
using System.Text;
using SharpGLTF.Runtime;
using SharpGLTF.Schema2;
using SharpGLTF.Transforms;
using SkiaSharp;

namespace Deadcraft.HeroExport;

/// <summary>
/// Packs an exported hero .glb into the small file the Minecraft client draws (format below, read by
/// fabric-client's HeroModel). One simplified mesh, the skeleton, the animation clips the client uses
/// as each joint's local transform at a fixed rate (so the client blends per joint and can add layers,
/// such as the upper body turning to the camera), and one downscaled texture per material beside it.
///
/// <code>
/// "DCHM" int32 version=2
/// int32 materials; per material: string name, string texture file (beside the pack, or "")
/// int32 vertices; per vertex: float3 position (metres, glTF axes), float3 normal, float2 uv,
///                 uint32 colour (RGBA8), uint16x4 joints (skinned joint index), float4 weights
/// per material: int32 triangles, int32x3 indices each
/// int32 nodes; per node, parents first: string name, int32 parent (-1 for none),
///                 rest pose float10 (translation xyz, rotation quaternion xyzw, scale xyz)
/// int32 skinned joints; per joint: int32 node, float12 inverse bind (3x4 rows: x' = row0 . (x, y, z, 1), ...)
/// int32 clips; per clip: string name, float fps, int32 frames, byte loop,
///                 frames x nodes x float10 (each node's local transform, as the rest pose)
/// </code>
/// Strings are int32 byte length + UTF-8. Little-endian throughout.
/// </summary>
internal static class HeroPack
{
	public const int Version = 2;
	private const float Fps = 30f;
	private const int TextureMax = 1024;

	/// <summary>Short clip names to keep, and whether each loops.</summary>
	private static readonly (string Name, bool Loop)[] Clips =
	[
		("out_of_combat_stand_idle", true), ("out_of_combat_crouch_idle", true), ("weapon_stand_idle", true),
		("out_of_combat_run_n", true), ("out_of_combat_run_ne", true), ("out_of_combat_run_e", true), ("out_of_combat_run_se", true),
		("out_of_combat_run_s", true), ("out_of_combat_run_sw", true), ("out_of_combat_run_w", true), ("out_of_combat_run_nw", true),
		("out_of_combat_crouch_run_n", true), ("out_of_combat_crouch_run_ne", true), ("out_of_combat_crouch_run_e", true),
		("out_of_combat_crouch_run_se", true), ("out_of_combat_crouch_run_s", true), ("out_of_combat_crouch_run_sw", true),
		("out_of_combat_crouch_run_w", true), ("out_of_combat_crouch_run_nw", true),
		("jump_ground", false), ("jump_air", false), ("in_air_apex", false), ("in_air_loop_down", true), ("landing_impact_idle", false),
		("slide_start", false), ("slide_loop", true), ("slide_getup", false),
		("dash_ground", false), ("dash_air_forward", false), ("dash_air_back", false), ("dash_air_left", false), ("dash_air_right", false),
		("run_to_stop_stand", false),
		("mantle_32", false), ("mantle_64", false), ("mantle_96", false), ("mantle_128", false),
		("wall_attach_forward", false), ("wall_attach_left", false), ("wall_attach_right", false),
	];

	public static void Write(string glbPath, string outPath, int targetTriangles)
	{
		var model = ModelRoot.Load(glbPath);
		var template = SceneTemplate.Create(model.DefaultScene, new RuntimeOptions());
		var instance = template.CreateInstance();
		string dir = Path.GetDirectoryName(outPath)!, stem = Path.GetFileNameWithoutExtension(outPath);

		// ---- geometry: every primitive of every drawable, simplified per primitive ----
		var positions = new List<Vector3>();
		var normals = new List<Vector3>();
		var uvs = new List<Vector2>();
		var colours = new List<uint>();
		var joints = new List<(int, int, int, int)>();
		var weights = new List<Vector4>();
		var materials = new List<string>();
		var materialTris = new List<List<int>>();
		var jointIds = new Dictionary<(int Drawable, int Joint), int>();  // packed joint numbering

		int sourceTris = model.LogicalMeshes.Sum(m => m.Primitives.Sum(p => p.GetIndices().Count / 3));
		double keep = Math.Min(1.0, (double)targetTriangles / sourceTris);
		var drawables = instance.ToArray();
		for (int d = 0; d < drawables.Length; d++)
		{
			var drawable = drawables[d];
			var mesh = model.LogicalMeshes[drawable.Template.LogicalMeshIndex];
			foreach (var prim in mesh.Primitives)
			{
				var pos = prim.GetVertexAccessor("POSITION").AsVector3Array();
				var nrm = prim.GetVertexAccessor("NORMAL")?.AsVector3Array();
				var uv = prim.GetVertexAccessor("TEXCOORD_0")?.AsVector2Array();
				var col = prim.GetVertexAccessor("COLOR_0")?.AsColorArray();
				var jnt = prim.GetVertexAccessor("JOINTS_0")?.AsVector4Array();
				var wgt = prim.GetVertexAccessor("WEIGHTS_0")?.AsVector4Array();
				var indices = prim.GetIndices().Select(i => (int)i).ToArray();
				var pa = pos.ToArray();
				var kept = Simplifier.Simplify(pa, indices, Math.Max(16, (int)(indices.Length / 3 * keep)));
				Console.WriteLine($"pack:   {prim.Material?.Name}: {indices.Length / 3} -> {kept.Length / 3} triangles");

				// Only vertices the kept triangles use.
				var remap = new Dictionary<int, int>();
				string matName = prim.Material?.Name ?? "default";
				int mi = materials.IndexOf(matName);
				if (mi < 0)
				{
					materials.Add(matName);
					materialTris.Add([]);
					mi = materials.Count - 1;
				}
				foreach (int i in kept)
				{
					if (!remap.TryGetValue(i, out int ni))
					{
						ni = positions.Count;
						remap[i] = ni;
						positions.Add(pa[i]);
						normals.Add(nrm != null ? nrm[i] : Vector3.UnitY);
						uvs.Add(uv != null ? uv[i] : Vector2.Zero);
						colours.Add(col != null ? Rgba(col[i]) : 0xFFFFFFFFu);
						var j = jnt != null ? jnt[i] : Vector4.Zero;
						var wt = wgt != null ? wgt[i] : new Vector4(1, 0, 0, 0);
						joints.Add((Joint(d, (int)j.X, wt.X), Joint(d, (int)j.Y, wt.Y), Joint(d, (int)j.Z, wt.Z), Joint(d, (int)j.W, wt.W)));
						weights.Add(wt);
					}
					materialTris[mi].Add(ni);
				}
			}
		}
		int Joint(int drawable, int joint, float weight)
		{
			if (weight <= 0) return 0;
			if (!jointIds.TryGetValue((drawable, joint), out int id)) jointIds[(drawable, joint)] = id = jointIds.Count;
			return id;
		}
		int keptTris = materialTris.Sum(t => t.Count) / 3;
		Console.WriteLine($"pack: {sourceTris} -> {keptTris} triangles, {positions.Count} vertices, {jointIds.Count} joints, {materials.Count} materials");

		// ---- textures: base colour per material, at most TextureMax square ----
		var textureFiles = new List<string>();
		foreach (var name in materials)
		{
			var mat = model.LogicalMaterials.FirstOrDefault(m => m.Name == name);
			var image = mat?.FindChannel("BaseColor")?.Texture?.PrimaryImage;
			string file = "";
			if (image != null)
			{
				using var bitmap = SKBitmap.Decode(image.Content.Content.ToArray());
				if (bitmap != null)
				{
					float s = Math.Min(1f, (float)TextureMax / Math.Max(bitmap.Width, bitmap.Height));
					using var scaled = s < 1f ? bitmap.Resize(new SKImageInfo((int)(bitmap.Width * s), (int)(bitmap.Height * s)), new SKSamplingOptions(SKFilterMode.Linear, SKMipmapMode.Linear)) : bitmap.Copy();
					file = $"{stem}_{name}.png";
					using var png = scaled.Encode(SKEncodedImageFormat.Png, 100);
					File.WriteAllBytes(Path.Combine(dir, file), png.ToArray());
				}
			}
			textureFiles.Add(file);
		}

		// ---- skeleton: every joint the skins use and their ancestors, parents before children ----
		var armature = instance.Armature;
		var skinOf = drawables.Select(dr => model.LogicalNodes.First(n => n.Name == dr.Template.NodeName).Skin).ToArray();
		var wanted = new HashSet<int>();
		foreach (var (d, j) in jointIds.Keys)
		{
			for (var n = skinOf[d].GetJoint(j).Joint; n != null; n = n.VisualParent) wanted.Add(n.LogicalIndex);
		}
		int Depth(int logical)
		{
			int k = 0;
			for (var n = model.LogicalNodes[logical].VisualParent; n != null; n = n.VisualParent) k++;
			return k;
		}
		var nodes = wanted.OrderBy(Depth).ThenBy(i => i).ToList();
		var nodeIndex = nodes.Select((logical, i) => (logical, i)).ToDictionary(p => p.logical, p => p.i);
		var parents = nodes.Select(l => model.LogicalNodes[l].VisualParent is { } p && nodeIndex.TryGetValue(p.LogicalIndex, out int pi) ? pi : -1).ToArray();
		var skinTable = new (int Node, Matrix4x4 InverseBind)[jointIds.Count];
		foreach (var ((d, j), id) in jointIds)
		{
			var (joint, inverseBind) = skinOf[d].GetJoint(j);
			skinTable[id] = (nodeIndex[joint.LogicalIndex], inverseBind);
		}
		armature.SetPoseTransforms();
		var rest = nodes.Select(l => Decompose(armature.LogicalNodes[l].LocalMatrix)).ToArray();
		// Clips carry their own travel on the root_motion joint (a dash moves the body forward). The real
		// movement comes from Deadlock, so root_motion keeps its rest position (its turn stays), and the
		// joints beside it lose the same travel.
		int rootMotion = nodes.FindIndex(l => model.LogicalNodes[l].Name == "root_motion");
		Console.WriteLine($"pack: skeleton {nodes.Count} nodes, {skinTable.Length} skinned joints" + (rootMotion < 0 ? ", no root_motion joint" : ""));

		// ---- animations: each node's local translation, rotation and scale, sampled at Fps ----
		var tracks = armature.AnimationTracks;
		var clipData = new List<(string Name, bool Loop, int Frames, float[] Locals)>();
		double worstCheck = 0;
		string worstAt = "";
		foreach (var (clipName, loop) in Clips)
		{
			int track = -1;
			for (int t = 0; t < tracks.Count; t++)
				if (tracks[t].Name == clipName || tracks[t].Name.EndsWith("/" + clipName, StringComparison.Ordinal)) { track = t; break; }
			if (track < 0)
			{
				Console.WriteLine($"pack: no clip {clipName}");
				continue;
			}
			float duration = tracks[track].Duration;
			int frames = Math.Max(1, (int)Math.Round(duration * Fps) + (loop ? 0 : 1));
			var locals = new float[frames * nodes.Count * 10];
			var frameLocals = new Trs[frames][];
			for (int f = 0; f < frames; f++)
			{
				armature.SetAnimationFrame(track, Math.Min(duration, f / Fps), loop);
				var local = nodes.Select(l => Decompose(armature.LogicalNodes[l].LocalMatrix)).ToArray();
				double err = Check(local, instance.ToArray());
				if (err > worstCheck) { worstCheck = err; worstAt = $"{clipName} frame {f}"; }
				if (rootMotion >= 0)
				{
					// The same travel is baked into the joints beside root_motion (196 cloth bones for Celeste,
					// simulated in the clip's space): take it off them too, or the skirt is left metres behind.
					var travel = local[rootMotion].T - rest[rootMotion].T;
					for (int n = 0; n < nodes.Count; n++)
						if (n != rootMotion && parents[n] == parents[rootMotion]) local[n].T -= travel;
					local[rootMotion].T = rest[rootMotion].T;
					// Clips also differ in root_motion's orientation (standing idle at rest, the others turned
					// 120 degrees, each compensated in the pelvis), so blending idle with anything twisted the
					// whole body mid-blend. Re-express every clip with root_motion at its rest orientation and
					// its children carrying the difference: world positions don't change.
					var clipRoot = Compose(local[rootMotion]);
					if (Matrix4x4.Invert(Compose(rest[rootMotion]), out var restRootInverse))
					{
						var change = clipRoot * restRootInverse;  // row vectors: child-local * change = child under the rest root
						for (int n = 0; n < nodes.Count; n++)
							if (parents[n] == rootMotion) local[n] = Decompose(Compose(local[n]) * change);
						local[rootMotion].R = rest[rootMotion].R;
						local[rootMotion].S = rest[rootMotion].S;
					}
				}
				frameLocals[f] = local;
			}
			int stillCloth = PinStillClothToHips(frameLocals);
			if (stillCloth > 0) Console.WriteLine($"pack:   {clipName}: {stillCloth} cloth bones don't move in this clip; they follow the hips");
			for (int f = 0; f < frames; f++)
			{
				for (int n = 0; n < nodes.Count; n++) Put(locals, (f * nodes.Count + n) * 10, frameLocals[f][n]);
				if (clipName == "dash_ground" && f == 15) WriteReference(frameLocals[f]);
			}
			clipData.Add((clipName, loop, frames, locals));
		}
		Console.WriteLine($"pack: {clipData.Count} clips, {clipData.Sum(c => c.Frames)} frames; rebuilt skinning differs from the exporter's by at most {worstCheck:E1} ({worstAt})");
		if (worstCheck > 1e-3) throw new InvalidOperationException("skeleton rebuild doesn't match the exporter's skinning; refusing to write a broken pack");

		// ---- write ----
		using var w = new BinaryWriter(File.Create(outPath));
		w.Write(Encoding.ASCII.GetBytes("DCHM"));
		w.Write(Version);
		w.Write(materials.Count);
		for (int i = 0; i < materials.Count; i++)
		{
			Str(materials[i]);
			Str(textureFiles[i]);
		}
		w.Write(positions.Count);
		for (int i = 0; i < positions.Count; i++)
		{
			w.Write(positions[i].X); w.Write(positions[i].Y); w.Write(positions[i].Z);
			w.Write(normals[i].X); w.Write(normals[i].Y); w.Write(normals[i].Z);
			w.Write(uvs[i].X); w.Write(uvs[i].Y);
			w.Write(colours[i]);
			var (a, b, c, e) = joints[i];
			w.Write((ushort)a); w.Write((ushort)b); w.Write((ushort)c); w.Write((ushort)e);
			w.Write(weights[i].X); w.Write(weights[i].Y); w.Write(weights[i].Z); w.Write(weights[i].W);
		}
		foreach (var tris in materialTris)
		{
			w.Write(tris.Count / 3);
			foreach (int i in tris) w.Write(i);
		}
		w.Write(nodes.Count);
		var restFloats = new float[10];
		for (int n = 0; n < nodes.Count; n++)
		{
			Str(model.LogicalNodes[nodes[n]].Name ?? "");
			w.Write(parents[n]);
			Put(restFloats, 0, rest[n]);
			foreach (float f in restFloats) w.Write(f);
		}
		w.Write(skinTable.Length);
		foreach (var (node, ib) in skinTable)
		{
			w.Write(node);
			// System.Numerics is row-vector (v * M): rows of the 3x4 are M's columns.
			foreach (float f in new[] { ib.M11, ib.M21, ib.M31, ib.M41, ib.M12, ib.M22, ib.M32, ib.M42, ib.M13, ib.M23, ib.M33, ib.M43 }) w.Write(f);
		}
		w.Write(clipData.Count);
		foreach (var (name, loop, frames, locals) in clipData)
		{
			Str(name);
			w.Write(Fps);
			w.Write(frames);
			w.Write((byte)(loop ? 1 : 0));
			foreach (float f in locals) w.Write(f);
		}
		Console.WriteLine($"pack: wrote {outPath} ({w.BaseStream.Length / 1024} KiB)");

		void Str(string s)
		{
			var bytes = Encoding.UTF8.GetBytes(s);
			w.Write(bytes.Length);
			w.Write(bytes);
		}

		// Deadlock simulates the cloth (Celeste's skirt) live, and some clips (landing, stopping, wall
		// braces) carry no cloth motion: those bones keep their standing pose while the body crouches or
		// leans, stretching the skirt into spikes. In such a clip, each bone beside root_motion that never
		// moves instead keeps its rest placement relative to the pelvis. Returns how many were pinned.
		int PinStillClothToHips(Trs[][] frameLocals)
		{
			int pelvis = nodes.FindIndex(l => model.LogicalNodes[l].Name == "pelvis");
			if (rootMotion < 0 || pelvis < 0) return 0;
			var still = new List<int>();
			for (int n = 0; n < nodes.Count; n++)
			{
				if (n == rootMotion || parents[n] != parents[rootMotion]) continue;
				bool moves = false;
				for (int f = 1; f < frameLocals.Length && !moves; f++)
					moves = Math.Abs(Quaternion.Dot(frameLocals[f][n].R, frameLocals[0][n].R)) < 0.99999f;
				if (!moves) still.Add(n);  // frozen for the whole clip (not necessarily at its rest pose)
			}
			if (still.Count == 0) return 0;
			var restWorld = Worlds(rest);
			Matrix4x4.Invert(restWorld[pelvis], out var restPelvisInverse);
			foreach (var local in frameLocals)
			{
				var world = Worlds(local);
				foreach (int n in still)
				{
					var target = restWorld[n] * restPelvisInverse * world[pelvis];  // row vectors: rest offset, then the hips
					var parentWorld = parents[n] < 0 ? Matrix4x4.Identity : world[parents[n]];
					Matrix4x4.Invert(parentWorld, out var parentInverse);
					local[n] = Decompose(target * parentInverse);
				}
			}
			return still.Count;
		}

		Matrix4x4[] Worlds(Trs[] local)
		{
			var world = new Matrix4x4[nodes.Count];
			for (int n = 0; n < nodes.Count; n++)
			{
				var m = Matrix4x4.CreateScale(local[n].S) * Matrix4x4.CreateFromQuaternion(local[n].R) * Matrix4x4.CreateTranslation(local[n].T);
				world[n] = parents[n] < 0 ? m : m * world[parents[n]];
			}
			return world;
		}

		// For the client's tests: the skinning matrices (3x4 rows) of dash_ground frame 15, as built here.
		void WriteReference(Trs[] local)
		{
			var world = new Matrix4x4[nodes.Count];
			for (int n = 0; n < nodes.Count; n++)
			{
				var m = Matrix4x4.CreateScale(local[n].S) * Matrix4x4.CreateFromQuaternion(local[n].R) * Matrix4x4.CreateTranslation(local[n].T);
				world[n] = parents[n] < 0 ? m : m * world[parents[n]];
			}
			using var r = new BinaryWriter(File.Create(Path.ChangeExtension(outPath, ".reference")));
			r.Write(skinTable.Length);
			foreach (var (node, ib) in skinTable)
			{
				var x = ib * world[node];
				foreach (float v in new[] { x.M11, x.M21, x.M31, x.M41, x.M12, x.M22, x.M32, x.M42, x.M13, x.M23, x.M33, x.M43 }) r.Write(v);
			}
		}

		// Rebuilds skinning matrices from the locals, as the Minecraft client will, and compares them with
		// the exporter's own: the largest difference in any matrix element.
		double Check(Trs[] local, DrawableInstance[] posed)
		{
			var world = new Matrix4x4[nodes.Count];
			for (int n = 0; n < nodes.Count; n++)
			{
				var m = Matrix4x4.CreateScale(local[n].S) * Matrix4x4.CreateFromQuaternion(local[n].R) * Matrix4x4.CreateTranslation(local[n].T);
				world[n] = parents[n] < 0 ? m : m * world[parents[n]];
			}
			double worst = 0;
			foreach (var ((d, j), id) in jointIds)
			{
				var mine = skinTable[id].InverseBind * world[skinTable[id].Node];
				var theirs = ((SkinnedTransform)posed[d].Transform).SkinMatrices[j];
				for (int r = 0; r < 4; r++)
					for (int c = 0; c < 3; c++) worst = Math.Max(worst, Math.Abs(mine[r, c] - theirs[r, c]));
			}
			return worst;
		}
	}

	private struct Trs
	{
		public Vector3 T, S;
		public Quaternion R;
	}

	private static Matrix4x4 Compose(Trs x) =>
		Matrix4x4.CreateScale(x.S) * Matrix4x4.CreateFromQuaternion(x.R) * Matrix4x4.CreateTranslation(x.T);

	private static Trs Decompose(Matrix4x4 m)
	{
		if (!Matrix4x4.Decompose(m, out var s, out var r, out var t)) (s, r, t) = (Vector3.One, Quaternion.Identity, m.Translation);
		return new Trs { T = t, R = Quaternion.Normalize(r), S = s };
	}

	// T xyz, R xyzw, S xyz.
	private static void Put(float[] a, int o, Trs x)
	{
		a[o] = x.T.X; a[o + 1] = x.T.Y; a[o + 2] = x.T.Z;
		a[o + 3] = x.R.X; a[o + 4] = x.R.Y; a[o + 5] = x.R.Z; a[o + 6] = x.R.W;
		a[o + 7] = x.S.X; a[o + 8] = x.S.Y; a[o + 9] = x.S.Z;
	}

	private static uint Rgba(Vector4 c) =>
		(uint)(Math.Clamp(c.X, 0, 1) * 255) << 24 | (uint)(Math.Clamp(c.Y, 0, 1) * 255) << 16
		| (uint)(Math.Clamp(c.Z, 0, 1) * 255) << 8 | (uint)(Math.Clamp(c.W, 0, 1) * 255);
}
