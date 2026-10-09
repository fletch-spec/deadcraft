using System.Numerics;
using System.Text;
using SharpGLTF.Runtime;
using SharpGLTF.Schema2;
using SharpGLTF.Transforms;
using SkiaSharp;

namespace Deadcraft.HeroExport;

/// <summary>
/// Packs an exported hero .glb into the small file the Minecraft client draws (format below, read by
/// fabric-client's HeroModel). One simplified mesh, the animation clips the client uses baked to
/// skinning matrices at a fixed rate, and one downscaled texture per material, written beside it.
///
/// <code>
/// "DCHM" int32 version=1
/// int32 materials; per material: string name, string texture file (beside the pack, or "")
/// int32 vertices; per vertex: float3 position (metres, glTF axes), float3 normal, float2 uv,
///                 uint32 colour (RGBA8), uint16x4 joints (packed joint index), float4 weights
/// per material: int32 triangles, int32x3 indices each
/// int32 joints
/// int32 clips; per clip: string name, float fps, int32 frames, byte loop,
///                 frames x joints x float12 (3x4 rows: x' = row0 . (x, y, z, 1), ...)
/// </code>
/// Strings are int32 byte length + UTF-8. Little-endian throughout.
/// </summary>
internal static class HeroPack
{
	public const int Version = 1;
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

		// ---- animations: skinning matrices per packed joint, sampled at Fps ----
		// Clips carry their own travel on the root_motion joint (a dash moves the body forward). The real
		// movement comes from Deadlock, so every frame is moved back by root_motion's travel.
		var tracks = instance.Armature.AnimationTracks;
		var rootNode = instance.Armature.LogicalNodes.FirstOrDefault(n => n.Name == "root_motion");
		instance.Armature.SetPoseTransforms();
		var rootRest = rootNode?.ModelMatrix ?? Matrix4x4.Identity;
		Console.WriteLine(rootNode == null ? "pack: no root_motion joint; clips keep their travel" : "pack: removing root motion");
		var clipData = new List<(string Name, bool Loop, int Frames, float[] Matrices)>();
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
			var m = new float[frames * jointIds.Count * 12];
			for (int f = 0; f < frames; f++)
			{
				instance.Armature.SetAnimationFrame(track, Math.Min(duration, f / Fps), loop);
				var posed = instance.ToArray();  // enumerating refreshes the drawables' transforms
				var cancel = Matrix4x4.Identity;
				if (rootNode != null)
				{
					// Travel only; the root's turn stays (Deadlock supplies every position change, mantles included).
					var travel = rootNode.ModelMatrix.Translation - rootRest.Translation;
					cancel = Matrix4x4.CreateTranslation(-travel);
				}
				foreach (var ((d, j), id) in jointIds)
				{
					var skin = (SkinnedTransform)posed[d].Transform;
					var x = skin.SkinMatrices[j] * cancel;  // row vectors: skin first, then the correction
					int o = (f * jointIds.Count + id) * 12;
					// System.Numerics is row-vector (v * M): rows of the 3x4 are M's columns.
					m[o] = x.M11; m[o + 1] = x.M21; m[o + 2] = x.M31; m[o + 3] = x.M41;
					m[o + 4] = x.M12; m[o + 5] = x.M22; m[o + 6] = x.M32; m[o + 7] = x.M42;
					m[o + 8] = x.M13; m[o + 9] = x.M23; m[o + 10] = x.M33; m[o + 11] = x.M43;
				}
			}
			clipData.Add((clipName, loop, frames, m));
		}
		Console.WriteLine($"pack: {clipData.Count} clips, {clipData.Sum(c => c.Frames)} frames");

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
		w.Write(jointIds.Count);
		w.Write(clipData.Count);
		foreach (var (name, loop, frames, m) in clipData)
		{
			Str(name);
			w.Write(Fps);
			w.Write(frames);
			w.Write((byte)(loop ? 1 : 0));
			foreach (float f in m) w.Write(f);
		}
		Console.WriteLine($"pack: wrote {outPath} ({w.BaseStream.Length / 1024} KiB)");

		void Str(string s)
		{
			var bytes = Encoding.UTF8.GetBytes(s);
			w.Write(bytes.Length);
			w.Write(bytes);
		}
	}

	private static uint Rgba(Vector4 c) =>
		(uint)(Math.Clamp(c.X, 0, 1) * 255) << 24 | (uint)(Math.Clamp(c.Y, 0, 1) * 255) << 16
		| (uint)(Math.Clamp(c.Z, 0, 1) * 255) << 8 | (uint)(Math.Clamp(c.W, 0, 1) * 255);
}
