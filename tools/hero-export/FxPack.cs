using System.IO.Compression;
using System.Text;
using System.Text.RegularExpressions;
using SkiaSharp;
using SteamDatabase.ValvePak;
using ValveKeyValue;
using ValveResourceFormat;
using ValveResourceFormat.IO;
using ValveResourceFormat.ResourceTypes;

namespace Deadcraft.HeroExport;

/// <summary>
/// Packs a hero's visual effects for the Minecraft client (fabric-client's dev.deadcraft.client.fx), from
/// the player's own Deadlock install; never committed. A zip (&lt;hero&gt;.dcfx) holding:
/// <code>
/// manifest.txt          "version 1", "scale &lt;f&gt;" (the model's particle scale),
///                       "effect &lt;ability&gt; &lt;key&gt; &lt;path&gt;" for every effect the hero's abilities name
///                       (Celeste's gun: citadel_weapon_unicorn_set m_szBulletTravelTracerParticle ...),
///                       "ambient &lt;path&gt;" for the model's own (the horn sparkle)
/// attachments.txt       per influence: "&lt;attachment&gt; &lt;bone&gt; ox oy oz qx qy qz qw weight"
///                       (Source units, in the bone's frame)
/// effects/&lt;path&gt;.kv3    each particle system as KV3 text (Deadlock's own definition, every child included)
/// textures/&lt;path&gt;.png   each texture they draw with, and textures/&lt;path&gt;.sheet for sprite sheets:
///                       per sequence "sequence &lt;id&gt; &lt;clamp 0|1&gt; &lt;total time&gt;", then per frame
///                       "frame &lt;display time&gt; u0 v0 u1 v1" (the first image, cropped, in 0..1)
/// </code>
/// The roots are every effect named by the hero's bound abilities, the model's ambient effects, and every
/// system in the hero's own particle folders (animation effects, such as the reload's, are started by
/// animation tags, not ability data).
/// </summary>
internal static class FxPack
{
	public const int Version = 1;
	private const int TextureMax = 512;
	private static readonly Regex ChildRef = new("resource:\"([^\"]+\\.vpcf)\"", RegexOptions.Compiled);
	private static readonly Regex TextureRef = new("resource:\"([^\"]+\\.vtex)\"", RegexOptions.Compiled);

	public static void Write(Package package, GameFileLoader loader, string hero, string modelPath, string outPath)
	{
		var manifest = new StringBuilder($"version {Version}\n");
		var roots = new List<string>();

		// The model: its particle scale, its ambient effects, its attachments.
		var model = loader.LoadFileCompiled(modelPath) ?? throw new InvalidOperationException($"could not load {modelPath}");
		var modelData = (Model)model.DataBlock!;
		var keyValues = modelData.KeyValues;
		float scale = 1;
		if (keyValues.TryGetValue("CitadelModelParticleSettings_t", out var settings) && settings.TryGetValue("m_flScale", out var s))
			scale = (float)s;
		manifest.Append(FormattableString.Invariant($"scale {scale}\n"));
		if (keyValues.TryGetValue("particle_cfg_list", out var ambient))
		{
			foreach (var entry in ambient.Values)
			{
				if (!entry.TryGetValue("name", out var name)) continue;
				string path = (string)name;
				manifest.Append($"ambient {path}\n");
				roots.Add(path);
			}
		}
		var attachments = new StringBuilder();
		foreach (var (name, attachment) in modelData.Attachments)
		{
			foreach (var influence in attachment)
			{
				attachments.Append(FormattableString.Invariant(
					$"{name} {influence.Name} {influence.Offset.X} {influence.Offset.Y} {influence.Offset.Z} {influence.Rotation.X} {influence.Rotation.Y} {influence.Rotation.Z} {influence.Rotation.W} {influence.Weight}\n"));
			}
		}

		// The hero's abilities, from heroes.vdata, and every effect they name in abilities.vdata.
		var heroes = Data(loader, "scripts/heroes.vdata");
		var abilities = Data(loader, "scripts/abilities.vdata");
		if (heroes.TryGetValue("hero_" + hero, out var heroData) && heroData.TryGetValue("m_mapBoundAbilities", out var bound))
		{
			foreach (var ability in bound.Values)
			{
				string abilityName = (string)ability;
				if (!abilities.TryGetValue(abilityName, out var abilityData)) continue;
				foreach (var (key, path) in EffectsIn(abilityData, ""))
				{
					manifest.Append($"effect {abilityName} {key} {path}\n");
					roots.Add(path);
				}
			}
		}
		else
		{
			Console.Error.WriteLine($"fx: no hero_{hero} abilities in heroes.vdata");
		}

		// Every system in the hero's own folders.
		if (package.Entries?.TryGetValue("vpcf_c", out var systems) == true)
		{
			foreach (var e in systems)
			{
				if (e.DirectoryName == null) continue;
				if (e.DirectoryName.StartsWith("particles/", StringComparison.Ordinal) && e.DirectoryName.EndsWith("/" + hero, StringComparison.Ordinal))
					roots.Add(e.GetFullPath()[..^2]);
			}
		}

		// Every system reachable from the roots, and the textures they draw with.
		var effects = new SortedDictionary<string, string>(StringComparer.Ordinal);
		var textures = new SortedSet<string>(StringComparer.Ordinal);
		var queue = new Queue<string>(roots);
		while (queue.Count > 0)
		{
			string path = queue.Dequeue();
			if (effects.ContainsKey(path)) continue;
			var res = loader.LoadFileCompiled(path);
			if (res?.DataBlock == null)
			{
				Console.Error.WriteLine($"fx: missing {path}");
				effects[path] = "";
				continue;
			}
			string text = res.DataBlock.ToString()!;
			effects[path] = text;
			foreach (Match m in ChildRef.Matches(text)) queue.Enqueue(m.Groups[1].Value);
			foreach (Match m in TextureRef.Matches(text)) textures.Add(m.Groups[1].Value);
		}

		string tmp = outPath + ".tmp";
		using (var zip = ZipFile.Open(tmp, ZipArchiveMode.Create))
		{
			Text(zip, "manifest.txt", manifest.ToString());
			Text(zip, "attachments.txt", attachments.ToString());
			int written = 0;
			foreach (var (path, text) in effects)
			{
				if (text.Length == 0) continue;
				Text(zip, "effects/" + path + ".kv3", text);
				written++;
			}
			int images = 0;
			foreach (var path in textures)
			{
				if (WriteTexture(zip, loader, path)) images++;
			}
			Console.WriteLine($"fx: {written} effects, {images} textures, {modelData.Attachments.Count} attachments, scale {scale}");
		}
		File.Move(tmp, outPath, true);
		Console.WriteLine($"wrote {outPath} ({new FileInfo(outPath).Length / 1024} KiB)");
	}

	/// <summary>Every "&lt;key&gt; = resource_name:...vpcf" under an ability, keyed by its path of keys.</summary>
	private static IEnumerable<(string Key, string Path)> EffectsIn(KVObject node, string prefix)
	{
		if (!node.IsCollection && !node.IsArray) yield break;
		int index = 0;
		foreach (var (key, child) in node.Children)
		{
			string name = prefix.Length == 0 ? key ?? index.ToString() : prefix + "." + (key ?? index.ToString());
			index++;
			if (child.IsCollection || child.IsArray)
			{
				foreach (var found in EffectsIn(child, name)) yield return found;
			}
			else if (child.ValueType == KVValueType.String)
			{
				string value = (string)child;
				if (value.EndsWith(".vpcf", StringComparison.Ordinal)) yield return (name, value);
			}
		}
	}

	private static KVObject Data(GameFileLoader loader, string path)
	{
		var res = loader.LoadFileCompiled(path) ?? throw new InvalidOperationException($"could not load {path}");
		return res.DataBlock switch
		{
			BinaryKV3 kv3 => kv3.Data,
			KeyValuesOrNTRO kv => kv.Data,
			var other => throw new InvalidOperationException($"{path}: unexpected {other?.GetType().Name}"),
		};
	}

	private static bool WriteTexture(ZipArchive zip, GameFileLoader loader, string path)
	{
		var res = loader.LoadFileCompiled(path);
		if (res?.DataBlock is not Texture texture)
		{
			Console.Error.WriteLine($"fx: missing texture {path}");
			return false;
		}
		using var full = texture.GenerateBitmap();
		SKBitmap bitmap = full;
		SKBitmap? scaled = null;
		int max = Math.Max(full.Width, full.Height);
		if (max > TextureMax)
		{
			float f = (float)TextureMax / max;
			scaled = full.Resize(new SKImageInfo(Math.Max(1, (int)(full.Width * f)), Math.Max(1, (int)(full.Height * f))), new SKSamplingOptions(SKFilterMode.Linear, SKMipmapMode.Linear));
			bitmap = scaled;
		}
		using (var stream = zip.CreateEntry("textures/" + path + ".png", CompressionLevel.Optimal).Open())
			bitmap.Encode(stream, SKEncodedImageFormat.Png, 100);
		scaled?.Dispose();

		var sheet = texture.GetSpriteSheetData();
		if (sheet != null && sheet.Sequences.Length > 0)
		{
			var text = new StringBuilder();
			foreach (var sequence in sheet.Sequences)
			{
				text.Append(FormattableString.Invariant($"sequence {sequence.Id} {(sequence.Clamp ? 1 : 0)} {sequence.TotalTime}\n"));
				foreach (var frame in sequence.Frames)
				{
					if (frame.Images.Length == 0) continue;
					var image = frame.Images[0];
					text.Append(FormattableString.Invariant(
						$"frame {frame.DisplayTime} {image.CroppedMin.X} {image.CroppedMin.Y} {image.CroppedMax.X} {image.CroppedMax.Y}\n"));
				}
			}
			Text(zip, "textures/" + path + ".sheet", text.ToString());
		}
		return true;
	}

	private static void Text(ZipArchive zip, string name, string text)
	{
		using var writer = new StreamWriter(zip.CreateEntry(name, CompressionLevel.Optimal).Open(), new UTF8Encoding(false));
		writer.Write(text);
	}
}
