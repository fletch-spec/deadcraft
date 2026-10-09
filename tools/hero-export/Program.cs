// Exports a Deadlock hero (model, materials, textures, animations) from the player's own Deadlock
// install to a local .glb, for the Minecraft client to draw. Nothing exported is ever committed or
// distributed: every player runs this against their own install.
//
//   dotnet run --project tools/hero-export <hero> [--deadlock <install dir>] [--out <dir>] [--triangles <n>] [--pack-only]
//
// <hero> is Deadlock's internal name (Celeste is "unicorn"; see citadel_gc_hero_names_english.txt).
// Output in %LOCALAPPDATA%\Deadcraft\heroes unless --out is given: <hero>.glb (the full export) and
// <hero>.dchero + <hero>_<material>.png (what Minecraft loads; see HeroPack). --pack-only repacks an
// existing .glb.

using System.Diagnostics;
using SteamDatabase.ValvePak;
using ValveResourceFormat.IO;

string? hero = null;
string deadlock = @"C:\Program Files (x86)\Steam\steamapps\common\Deadlock";
string outDir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Deadcraft", "heroes");
int triangles = 25000;
bool packOnly = false;
for (int i = 0; i < args.Length; i++)
{
	switch (args[i])
	{
		case "--deadlock": deadlock = args[++i]; break;
		case "--out": outDir = args[++i]; break;
		case "--triangles": triangles = int.Parse(args[++i]); break;
		case "--pack-only": packOnly = true; break;
		default: hero = args[i]; break;
	}
}
if (hero == null)
{
	Console.Error.WriteLine("usage: hero-export <hero> [--deadlock <install dir>] [--out <dir>] [--triangles <n>] [--pack-only]   (Celeste is \"unicorn\")");
	return 2;
}

Directory.CreateDirectory(outDir);
string outPath = Path.Combine(outDir, hero + ".glb");
string packPath = Path.Combine(outDir, hero + ".dchero");
if (packOnly)
{
	Deadcraft.HeroExport.HeroPack.Write(outPath, packPath, triangles);
	return 0;
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
var models = package.Entries?.TryGetValue("vmdl_c", out var entries) == true
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

using var loader = new GameFileLoader(package, vpk);
var resource = loader.LoadFileCompiled(modelPath) ?? throw new InvalidOperationException($"could not load {modelPath}");
var exporter = new GltfModelExporter(loader)
{
	ExportAnimations = true,
	ExportMaterials = true,
	ProgressReporter = new Progress<string>(Console.WriteLine),
};
var watch = Stopwatch.StartNew();
exporter.Export(resource, outPath, CancellationToken.None);
Console.WriteLine($"wrote {outPath} ({new FileInfo(outPath).Length / 1024} KiB) in {watch.Elapsed.TotalSeconds:F1} s");
Deadcraft.HeroExport.HeroPack.Write(outPath, packPath, triangles);
return 0;
