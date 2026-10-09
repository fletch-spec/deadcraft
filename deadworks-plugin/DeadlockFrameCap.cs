using System.Text.RegularExpressions;
using DeadworksManaged.Api;

namespace Deadcraft.Plugin;

// While a player is on the Deadcraft server, their Deadlock only feeds input: Minecraft covers its
// picture. Uncapped (fps_max 400 by default) it takes the GPU Minecraft needs. The cap is lowered
// each time the hero comes alive on the Deadcraft map. Deadlock saves fps_max, and a command sent as the
// client disconnects never arrives, so the player's normal cap comes back from a line in Deadlock's
// autoexec.cfg (fps_max 400), run at every launch.
//
// The cap is a balance per PC: high enough that Deadlock reads input smoothly (30 felt sluggish),
// low enough to leave Minecraft the GPU. Set it with the server cvar deadcraft_deadlock_fps_max.
internal static class DeadlockFrameCap
{
	private const int DefaultCap = 122;  // felt right on the development PC (Minecraft ~350 fps moving)
	private static ConVar? _cap;

	public static void Register() =>
		_cap ??= ConVar.Find("deadcraft_deadlock_fps_max")
			?? ConVar.Create("deadcraft_deadlock_fps_max", DefaultCap.ToString(), "Deadlock's fps_max while playing Deadcraft", false);

	public static int WhilePlaying() => _cap?.GetInt() is int v && v >= 30 ? v : DefaultCap;

	/// <summary>The player's own fps_max from Deadlock's saved settings, for the log; null if unreadable.</summary>
	public static int? Original(out string? problem)
	{
		problem = null;
		try
		{
			return ReadConfig();
		}
		catch (Exception e)
		{
			problem = e.Message;
			return null;
		}
	}

	// Steam\userdata\<account>\1422450\local\cfg\machine_convars.vcfg: "fps_max"  "400"
	private static int? ReadConfig()
	{
		// From the server, SteamsteamappsmmonDeadlockgamebinwin64deadworks.exe (AppContext.BaseDirectory is empty here).
		var exe = System.Diagnostics.Process.GetCurrentProcess().MainModule?.FileName;
		if (exe == null) return null;
		var dir = new DirectoryInfo(Path.GetDirectoryName(exe)!);
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
