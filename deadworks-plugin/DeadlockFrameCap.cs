using System.Text.RegularExpressions;

namespace Deadcraft.Plugin;

// While a player is on the Deadcraft server, their Deadlock only feeds input: Minecraft covers its
// picture. Uncapped (fps_max 400 by default) it takes the GPU Minecraft needs. The cap is lowered
// while connected and put back on disconnect.
//
// fps_max is saved by Deadlock, so the player's own value is remembered first: read from their
// machine_convars.vcfg (same PC as this server) and kept in %LOCALAPPDATA%\Deadcraft, so a restore that
// never arrived can't lose it.
internal static class DeadlockFrameCap
{
	public const int WhilePlaying = 144;
	private const int Fallback = 400;

	private static string StateFile => Path.Combine(
		Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Deadcraft", "deadlock-fps_max.txt");

	/// <summary>The player's own fps_max, to restore.</summary>
	public static int Original()
	{
		try
		{
			if (File.Exists(StateFile) && int.TryParse(File.ReadAllText(StateFile).Trim(), out int saved)) return saved;
			int fromConfig = ReadConfig() ?? Fallback;
			if (fromConfig == WhilePlaying) fromConfig = Fallback;  // our own value left behind: don't keep it
			Directory.CreateDirectory(Path.GetDirectoryName(StateFile)!);
			File.WriteAllText(StateFile, fromConfig.ToString());
			return fromConfig;
		}
		catch (Exception)
		{
			return Fallback;
		}
	}

	// Steam\userdata\<account>\1422450\local\cfg\machine_convars.vcfg: "fps_max"  "400"
	private static int? ReadConfig()
	{
		// The plugin runs from Steam\steamapps\common\Deadlock\game\bin\win64\managed\plugins.
		var dir = new DirectoryInfo(AppContext.BaseDirectory);
		while (dir != null && !string.Equals(dir.Name, "steamapps", StringComparison.OrdinalIgnoreCase)) dir = dir.Parent;
		var userdata = dir?.Parent is { } steam ? Path.Combine(steam.FullName, "userdata") : null;
		if (userdata == null || !Directory.Exists(userdata)) return null;
		var newest = Directory.GetDirectories(userdata)
			.Select(account => Path.Combine(account, "1422450", "local", "cfg", "machine_convars.vcfg"))
			.Where(File.Exists)
			.OrderByDescending(File.GetLastWriteTimeUtc)
			.FirstOrDefault();
		if (newest == null) return null;
		var match = Regex.Match(File.ReadAllText(newest), "\"fps_max\"\\s+\"([0-9.]+)\"");
		return match.Success && float.TryParse(match.Groups[1].Value, System.Globalization.CultureInfo.InvariantCulture, out float v) ? (int)v : null;
	}
}
