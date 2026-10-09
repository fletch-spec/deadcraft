using System.Text.RegularExpressions;
using DeadworksManaged.Api;

namespace Deadcraft.Plugin;

// While a player is on the Deadcraft server, their Deadlock only feeds input: Minecraft covers its
// picture. Uncapped (fps_max 400 by default) it takes the GPU Minecraft needs. The cap is lowered
// when the hero spawns on the Deadcraft map. Deadlock saves fps_max, so the player's own value is
// remembered (read from their machine_convars.vcfg, on the same PC as this server, and kept in
// %LOCALAPPDATA%\Deadcraft). A command sent as the client disconnects never arrives, so the restore for
// normal play is a line in Deadlock's autoexec.cfg.
//
// The cap is a balance per PC: high enough that Deadlock reads input smoothly (30 felt sluggish),
// low enough to leave Minecraft the GPU. Set it with the server cvar deadcraft_deadlock_fps_max.
internal static class DeadlockFrameCap
{
	private const int DefaultCap = 122;  // felt right on the development PC (Minecraft ~350 fps moving)
	private const int Fallback = 400;
	private static ConVar? _cap;

	public static void Register() =>
		_cap ??= ConVar.Find("deadcraft_deadlock_fps_max")
			?? ConVar.Create("deadcraft_deadlock_fps_max", DefaultCap.ToString(), "Deadlock's fps_max while playing Deadcraft", false);

	public static int WhilePlaying() => _cap?.GetInt() is int v && v >= 30 ? v : DefaultCap;

	private static string StateFile => Path.Combine(
		Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Deadcraft", "deadlock-fps_max.txt");

	/// <summary>The player's own fps_max, to restore.</summary>
	public static int Original(out string? problem)
	{
		problem = null;
		try
		{
			if (File.Exists(StateFile) && int.TryParse(File.ReadAllText(StateFile).Trim(), out int saved)) return saved;
			int fromConfig = ReadConfig() ?? Fallback;
			if (fromConfig == WhilePlaying() || fromConfig < 60) fromConfig = Fallback;  // a Deadcraft cap left behind: don't keep it
			Directory.CreateDirectory(Path.GetDirectoryName(StateFile)!);
			File.WriteAllText(StateFile, fromConfig.ToString());
			return fromConfig;
		}
		catch (Exception e)
		{
			problem = e.Message;
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
