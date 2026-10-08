package dev.deadcraft.client;

import dev.deadcraft.protocol.HeroFlags;
import dev.deadcraft.protocol.HeroState;
import dev.deadcraft.protocol.Mapping;
import dev.deadcraft.protocol.Proto;
import dev.deadcraft.protocol.Vec3;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Drives the local player from the Deadlock hero. While linked, Deadlock owns position and look:
 * vanilla movement is switched off ({@code LivingEntityMixin}) and the player is placed every frame.
 * When the bridge is down, nothing here touches the player, so vanilla movement is back.
 *
 * <p>Positions are relative: on linking, the hero's position is anchored to where the player stands,
 * and the player then moves by exactly the hero's displacement. {@code /deadcraft anchor} re-anchors.
 */
public final class Follow {
	static final Logger LOG = LoggerFactory.getLogger("deadcraft");

	/** A heartbeat older than this means the Deadlock side is gone. */
	private static final long STALE_MS = 500;
	private static final long REOPEN_INTERVAL_MS = 1000;

	private static Mapping mapping;
	private static long nextOpenAttempt;
	private static boolean enabled = true;
	private static boolean linked;
	private static boolean anchorRequested;
	private static double anchorX, anchorY, anchorZ;  // player position at anchoring
	private static Vec3 heroAnchor = Vec3.ZERO;       // hero position (in blocks) at anchoring
	private static boolean savedPauseOnLostFocus;
	private static String lastProblem = "";
	private static HeroState lastState;
	private static long lastGoodRead;

	private Follow() {}

	public static boolean linked() {
		return linked;
	}

	/** Called at the start of every frame, before the world is ticked and drawn. */
	public static void beginFrame() {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		Optional<HeroState> state = enabled && player != null ? readHero() : Optional.empty();
		long now = System.currentTimeMillis();
		if (state.isPresent()) {
			lastGoodRead = now;
		} else if (linked && enabled && lastState != null && lastProblem.isEmpty() && now - lastGoodRead < STALE_MS) {
			// One missed read (the plugin mid-write) is not an outage: hold the last state.
			state = Optional.of(lastState);
		}
		boolean shouldLink = state.isPresent() && player != null && player.isAlive();
		if (shouldLink != linked) setLinked(mc, shouldLink);
		if (!linked) return;

		HeroState hero = state.get();
		lastState = hero;
		Vec3 heroBlocks = Proto.toMinecraft(hero.position);
		if (anchorRequested) {
			anchorRequested = false;
			anchorX = player.getX();
			anchorY = player.getY();
			anchorZ = player.getZ();
			// A grounded hero anchors to the ground under the player, not wherever the player was
			// floating; otherwise Minecraft sees "on ground" in mid-air and keeps resending chunks.
			if ((hero.flags & HeroFlags.ON_GROUND) != 0) {
				double ground = groundBelow(mc, anchorX, anchorY, anchorZ);
				if (!Double.isNaN(ground)) anchorY = ground;
			}
			heroAnchor = heroBlocks;
		}
		double x = anchorX + (heroBlocks.x() - heroAnchor.x());
		double y = anchorY + (heroBlocks.y() - heroAnchor.y());
		double z = anchorZ + (heroBlocks.z() - heroAnchor.z());
		player.setPos(x, y, z);
		// Same old and new position: nothing left for Minecraft to interpolate, we place it every frame.
		player.xo = player.xOld = x;
		player.yo = player.yOld = y;
		player.zo = player.zOld = z;
		player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
		player.setOnGround((hero.flags & HeroFlags.ON_GROUND) != 0);
		player.resetFallDistance();

		if (mc.gui.screen() == null) {
			// Source yaw 0 faces +x (Minecraft east, yaw -90) and turns toward +y (Minecraft north).
			float yaw = -90f - hero.cameraAngles.y();
			float pitch = hero.cameraAngles.x();
			player.setYRot(yaw);
			player.setXRot(pitch);
			player.yRotO = yaw;
			player.xRotO = pitch;
			player.setYHeadRot(yaw);
			player.yHeadRotO = yaw;
			player.yBodyRot = yaw;
			player.yBodyRotO = yaw;
		}
	}

	/** Top of the first solid block at or below (x, y, z) within 16 blocks, or NaN. */
	private static double groundBelow(Minecraft mc, double x, double y, double z) {
		if (mc.level == null) return Double.NaN;
		BlockPos pos = BlockPos.containing(x, y, z);
		for (int i = 0; i <= 16; i++, pos = pos.below()) {
			if (!mc.level.isLoaded(pos)) return Double.NaN;
			VoxelShape shape = mc.level.getBlockState(pos).getCollisionShape(mc.level, pos);
			if (!shape.isEmpty()) {
				double top = pos.getY() + shape.max(Direction.Axis.Y);
				if (top <= y + 1e-6) return top;
			}
		}
		return Double.NaN;
	}

	private static Optional<HeroState> readHero() {
		if (mapping == null) {
			long now = System.currentTimeMillis();
			if (now < nextOpenAttempt) return Optional.empty();
			nextOpenAttempt = now + REOPEN_INTERVAL_MS;
			try {
				mapping = Mapping.openExisting().orElse(null);
				if (mapping != null) LOG.info("Deadcraft: bridge opened ({})", Proto.MAPPING_NAME);
			} catch (RuntimeException e) {
				problem("bridge refused: " + e.getMessage());
				return Optional.empty();
			}
			if (mapping == null) {
				problem("waiting for Deadlock (no " + Proto.MAPPING_NAME + ")");
				return Optional.empty();
			}
		}
		long age = mapping.deadlockHeartbeatAgeMs();
		if (age > STALE_MS) {
			problem("Deadlock bridge stale (" + age + " ms)");
			return Optional.empty();
		}
		Optional<HeroState> state = mapping.readHeroState();
		if (state.isPresent() && (state.get().flags & HeroFlags.PRESENT) == 0) {
			problem("Deadlock has no hero spawned");
			return Optional.empty();
		}
		if (state.isPresent()) lastProblem = "";
		return state;
	}

	private static void setLinked(Minecraft mc, boolean link) {
		linked = link;
		if (link) {
			anchorRequested = true;
			// Deadlock has the keyboard and mouse, so Minecraft must keep running without focus.
			savedPauseOnLostFocus = mc.options.pauseOnLostFocus;
			mc.options.pauseOnLostFocus = false;
			lastProblem = "";
			status(mc, "Deadcraft: following the Deadlock hero");
		} else {
			mc.options.pauseOnLostFocus = savedPauseOnLostFocus;
			status(mc, "Deadcraft: unlinked, vanilla movement" + (lastProblem.isEmpty() ? "" : " (" + lastProblem + ")"));
		}
		LOG.info("Deadcraft: {}", link ? "linked" : "unlinked " + lastProblem);
	}

	private static void problem(String text) {
		if (!text.equals(lastProblem)) LOG.info("Deadcraft: {}", text);
		lastProblem = text;
	}

	private static void status(Minecraft mc, String text) {
		if (mc.player != null) mc.player.sendOverlayMessage(Component.literal(text));
	}

	// ---- /deadcraft commands -------------------------------------------------------------------

	static String anchor() {
		anchorRequested = true;
		return linked ? "Re-anchored: the hero's position now maps to where you stand." : "Not linked; will anchor on link.";
	}

	static String setEnabled(boolean on) {
		enabled = on;
		return on ? "Following enabled." : "Following disabled: vanilla movement.";
	}

	static String status() {
		if (!linked) return "Unlinked" + (enabled ? "" : " (disabled)") + (lastProblem.isEmpty() ? "" : ": " + lastProblem);
		HeroState s = lastState;
		Vec3 b = Proto.toMinecraft(s.position);
		return String.format("Linked. Hero %d at Deadlock (%.0f, %.0f, %.0f) = %.2f, %.2f, %.2f blocks; tick %d; stamina %.2f/%.0f; hp %d/%d",
			s.heroId, s.position.x(), s.position.y(), s.position.z(), b.x(), b.y(), b.z(), s.tick, s.stamina, s.staminaMax, s.health, s.healthMax);
	}
}
