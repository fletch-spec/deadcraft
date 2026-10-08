package dev.deadcraft.protocol.cli;

import dev.deadcraft.protocol.AbilityEvent;
import dev.deadcraft.protocol.HeroFlags;
import dev.deadcraft.protocol.HeroState;
import dev.deadcraft.protocol.Mapping;
import dev.deadcraft.protocol.Proto;
import dev.deadcraft.protocol.Vec3;
import java.util.Optional;

/**
 * Prints the Deadlock hero's state from shared memory, live. Run with {@code gradlew run} (from
 * protocol/java) while a Deadworks server with the Deadcraft plugin is running.
 *
 * <p>Arguments: {@code --interval <ms>} between lines (default 250).
 */
public final class Watch {
	private Watch() {}

	public static void main(String[] args) throws InterruptedException {
		long interval = 250;
		for (int i = 0; i < args.length; i++) {
			if (args[i].equals("--interval") && i + 1 < args.length) interval = Long.parseLong(args[++i]);
		}

		System.out.println("Waiting for " + Proto.MAPPING_NAME + " (start the Deadworks server with the Deadcraft plugin)...");
		Mapping mapping = null;
		while (mapping == null) {
			Optional<Mapping> opened = Mapping.openExisting();
			if (opened.isPresent()) mapping = opened.get();
			else Thread.sleep(1000);
		}
		System.out.printf("Opened %s: protocol v%d, %.0f units per block, Deadlock pid %d%n",
			Proto.MAPPING_NAME, mapping.readHeader().version, mapping.readHeader().unitsPerBlock, mapping.readHeader().deadlockPid);

		int lastEvent = -1;
		long lastTick = -1;
		while (true) {
			long age = mapping.deadlockHeartbeatAgeMs();
			Optional<HeroState> read = mapping.readHeroState();
			if (read.isEmpty()) {
				System.out.println("(writer busy)");
			} else {
				HeroState s = read.get();
				if (lastEvent < 0) lastEvent = s.abilityEventSerial;
				for (int serial = lastEvent + 1; Integer.compareUnsigned(serial, s.abilityEventSerial) <= 0; serial++) {
					int shown = serial;
					mapping.readAbilityEvent(serial).ifPresentOrElse(
						e -> System.out.printf("  ABILITY #%d tick %d %s%n", shown, e.tick, e.abilityName),
						() -> System.out.printf("  ABILITY #%d (overwritten before it was read)%n", shown));
				}
				if (Integer.compareUnsigned(s.abilityEventSerial, lastEvent) < 0) {
					System.out.println("  (plugin restarted; event serials reset)");
				}
				lastEvent = s.abilityEventSerial;
				System.out.println(describe(s, age, s.tick == lastTick));
				lastTick = s.tick;
			}
			Thread.sleep(interval);
		}
	}

	static String describe(HeroState s, long heartbeatAgeMs, boolean tickUnchanged) {
		String link = heartbeatAgeMs > 1000 ? "STALE " + heartbeatAgeMs + "ms" : "live";
		if ((s.flags & HeroFlags.PRESENT) == 0) return String.format("[%s] tick %d  no hero", link, s.tick);
		Vec3 mc = Proto.toMinecraft(s.position);
		Vec3 v = s.velocity;
		double speed = Math.sqrt(v.x() * v.x() + v.y() * v.y()) / Proto.UNITS_PER_BLOCK;
		return String.format(
			"[%s] tick %d%s  hero %d  pos (%.0f, %.0f, %.0f) = mc (%.2f, %.2f, %.2f)  speed %.2f b/s  vz %.0f  %s%s  cam p%.1f y%.1f  stamina %.2f/%.0f  hp %d/%d",
			link, s.tick, tickUnchanged ? " (same)" : "", s.heroId,
			s.position.x(), s.position.y(), s.position.z(), mc.x(), mc.y(), mc.z(),
			speed, v.z(),
			(s.flags & HeroFlags.ALIVE) != 0 ? "alive" : "DEAD",
			(s.flags & HeroFlags.ON_GROUND) != 0 ? " ground" : " air",
			s.cameraAngles.x(), s.cameraAngles.y(), s.stamina, s.staminaMax, s.health, s.healthMax);
	}
}
