package dev.deadcraft.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;

/**
 * The hero's bullets hurting Minecraft's mobs. Shots are traced through Minecraft's world on the client
 * ({@code Follow.fired}); a bullet that meets a living entity hurts it when the drawn bullet gets there, through the
 * singleplayer server (the hero's player as the attacker, so kills and aggro count as theirs). Multiplayer needs a
 * packet to a server mod and the server's own check of the shot: not yet.
 *
 * <p>Deadlock's damage (Celeste's gun, abilities.vdata: 18 a bullet, falling off from 866 to 2362 units to a tenth;
 * no headshot bonus against NPCs) is scaled for Minecraft's health ({@code /deadcraft damage <scale>}, saved in
 * {@code config/deadcraft-combat.properties}): Deadlock heroes have hundreds of health, a zombie 20.
 */
public final class MobDamage {
	/** Minecraft health per point of Deadlock damage: 18 is 4.5 (about a stone sword's hit; a zombie in five shots). */
	public static final float DEFAULT_SCALE = 0.25f, MAX_SCALE = 5;
	/** Celeste's bullet: damage and its falloff (blocks, 64 units a block). */
	static final float GUN_DAMAGE = 18, FALLOFF_START = 866.142f / 64, FALLOFF_END = 2362.2f / 64, FALLOFF_END_SCALE = 0.1f;

	private static float scale = DEFAULT_SCALE;
	private static Path file;
	private static int hits, reported;
	private static float dealt;

	private record Pending(int entity, float health, long due) {}

	private static final List<Pending> pending = new ArrayList<>();

	private MobDamage() {
	}

	/** Deadlock's falloff: full damage to {@link #FALLOFF_START}, a tenth from {@link #FALLOFF_END}, straight between. */
	static float falloff(double blocks) {
		if (blocks <= FALLOFF_START) return 1;
		if (blocks >= FALLOFF_END) return FALLOFF_END_SCALE;
		return (float) (1 + (FALLOFF_END_SCALE - 1) * (blocks - FALLOFF_START) / (FALLOFF_END - FALLOFF_START));
	}

	/** Minecraft health a bullet takes, after {@code blocks} of flight. */
	static float health(float deadlockDamage, double blocks) {
		return deadlockDamage * falloff(blocks) * scale;
	}

	/** A bullet meets {@code entityId} after {@code blocks} of flight; it hurts it in {@code delayS} seconds. */
	static void hit(int entityId, float deadlockDamage, double blocks, double delayS) {
		float h = health(deadlockDamage, blocks);
		if (h <= 0) return;
		pending.add(new Pending(entityId, h, System.nanoTime() + (long) (delayS * 1e9)));
	}

	/** Every frame: hurts what the bullets that have arrived hit. */
	static void tick(Minecraft mc) {
		if (pending.isEmpty()) return;
		var server = mc.getSingleplayerServer();
		if (server == null || mc.player == null || mc.level == null) {
			if (reported++ == 0) Follow.LOG.info("Deadcraft: bullets don't hurt mobs here (not singleplayer)");
			pending.clear();
			return;
		}
		long now = System.nanoTime();
		var dimension = mc.level.dimension();
		var uuid = mc.player.getUUID();
		for (Iterator<Pending> it = pending.iterator(); it.hasNext();) {
			Pending p = it.next();
			if (p.due() > now) continue;
			it.remove();
			server.execute(() -> {
				var level = server.getLevel(dimension);
				var attacker = server.getPlayerList().getPlayer(uuid);
				if (level == null || attacker == null) return;
				if (level.getEntity(p.entity()) instanceof LivingEntity target && target.isAlive()) {
					target.hurtServer(level, target.damageSources().playerAttack(attacker), p.health());
				}
			});
			hits++;
			dealt += p.health();
		}
	}

	/** For the 10 s log line. */
	static String stats() {
		String s = String.format(Locale.ROOT, "mob hits %d (%.1f health)", hits, dealt);
		hits = 0;
		dealt = 0;
		return s;
	}

	static void load(Path from) {
		file = from;
		Properties p = new Properties();
		try (var in = Files.newBufferedReader(from)) {
			p.load(in);
			scale = Math.max(0, Math.min(MAX_SCALE, Float.parseFloat(p.getProperty("damageScale", "" + DEFAULT_SCALE))));
		} catch (IOException | NumberFormatException e) {
			// none saved yet
		}
	}

	static String setScale(float s) {
		scale = Math.max(0, Math.min(MAX_SCALE, s));
		if (file != null) {
			Properties p = new Properties();
			p.setProperty("damageScale", Float.toString(scale));
			try (var out = Files.newBufferedWriter(file)) {
				p.store(out, "Deadcraft: combat settings (/deadcraft damage)");
			} catch (IOException e) {
				Follow.LOG.warn("Deadcraft: couldn't save {}: {}", file, e.toString());
			}
		}
		return status();
	}

	static String status() {
		return String.format(Locale.ROOT, "Damage scale %.2f: a bullet takes %.1f health close up (%.1f hearts), %.1f at 37 blocks or more.", scale,
			health(GUN_DAMAGE, 0), health(GUN_DAMAGE, 0) / 2, health(GUN_DAMAGE, FALLOFF_END));
	}
}
