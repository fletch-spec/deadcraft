// Exports a Deadlock hero (model, materials, textures, animations) from the player's own Deadlock
// install to a local .glb, for the Minecraft client to draw. Nothing exported is ever committed or
// distributed: every player runs this against their own install.
//
//   dotnet run --project tools/hero-export <hero> [--deadlock <install dir>] [--out <dir>] [--no-anims]
//
// <hero> is Deadlock's internal name (Celeste is "unicorn"; see citadel_gc_hero_names_english.txt).
// Output: %LOCALAPPDATA%\Deadcraft\heroes\<hero>.glb unless --out is given.

using System.Diagnostics;
using SteamDatabase.ValvePak;
using ValveResourceFormat.IO;

string? hero = null;
string deadlock = @"C:\Program Files (x86)\Steam\steamapps\common\Deadlock";
string outDir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Deadcraft", "heroes");
bool anims = true;
for (int i = 0; i < args.Length; i++)
{
	switch (args[i])
	{
		case "--deadlock": deadlock = args[++i]; break;
		case "--out": outDir = args[++i]; break;
		case "--no-anims": anims = false; break;
		default: hero = args[i]; break;
	}
}
if (hero == null)
{
	Console.Error.WriteLine("usage: hero-export <hero> [--deadlock <install dir>] [--out <dir>] [--no-anims]   (Celeste is \"unicorn\")");
	return 2;
}

string vpk = Path.Combine(deadlock, "game", "citadel", "pak01_dir.vpk");
if (!File.Exists(vpk))
{
	Console.Error.WriteLine($"Deadlock not found: {vpk} (pass --deadlock <install dir>)");
	return 1;
}

using var package = new Package();
package.OptimizeEntriesForBinarySearch();
package.Read(vpk);

// The hero's model: models/heroes*/<hero>/<hero>.vmdl_c (heroes, heroes_wip, ...).
var models = package.Entries.TryGetValue("vmdl_c", out var entries)
	? entries.Where(e => e.DirectoryName != null && e.DirectoryName.StartsWith("models/heroes", StringComparison.Ordinal)
		&& e.DirectoryName.EndsWith("/" + hero, StringComparison.Ordinal) && e.FileName == hero).ToList()
	: [];
if (models.Count == 0)
{
	Console.Error.WriteLine($"no model for hero \"{hero}\" under models/heroes*/{hero}/{hero}.vmdl_c");
	return 1;
}
var model = models[0];
string modelPath = model.GetFullPath()[..^2];  // LoadFileCompiled adds the "_c"
Console.WriteLine($"model: {modelPath} ({model.TotalLength / 1024} KiB)");

Directory.CreateDirectory(outDir);
string outPath = Path.Combine(outDir, hero + ".glb");
using var loader = new GameFileLoader(package, vpk);
var resource = loader.LoadFileCompiled(modelPath) ?? throw new InvalidOperationException($"could not load {modelPath}");
var exporter = new GltfModelExporter(loader)
{
	ExportAnimations = anims,
	ExportMaterials = true,
	ProgressReporter = new Progress<string>(Console.WriteLine),
};
var watch = Stopwatch.StartNew();
exporter.Export(resource, outPath, CancellationToken.None);
Console.WriteLine($"wrote {outPath} ({new FileInfo(outPath).Length / 1024} KiB) in {watch.Elapsed.TotalSeconds:F1} s");
return 0;
