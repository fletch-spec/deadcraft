package dev.deadcraft.client;

import dev.deadcraft.protocol.Double3;
import dev.deadcraft.protocol.HeroFlags;
import dev.deadcraft.protocol.HeroState;
import dev.deadcraft.protocol.Mapping;
import dev.deadcraft.protocol.Proto;
import dev.deadcraft.protocol.Vec3;
import java.util.Optional;
import dev.deadcraft.client.hero.HeroAnimator;
import dev.deadcraft.client.hero.HeroRenderer;
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
	/** The hero moving this far in one frame without a recentre means respawn or teleport. */
	private static final double RESPAWN_JUMP_BLOCKS = 24;
	private static int recenterSerial;
	private static Vec3 lastDisplacement;
	private static final BlockExport export = new BlockExport();
	private static final HeroTimeline timeline = new HeroTimeline();
	private static final Overlay overlay = new Overlay();
	private static final RawMouse rawMouse = new RawMouse();
	private static final LookPredictor look = new LookPredictor(rawMouse);
	private static boolean rawLook = true;
	private static long lastLookTick = -1;
	private static int lastAbilitySerial = -1;
	private static final java.util.Set<String> abilitiesSeen = new java.util.HashSet<>();
	private static boolean savedBobView;
	private static long lastGoodRead;

	private Follow() {}

	public static boolean linked() {
		return linked;
	}

	/** Every client tick: heartbeat and block export to Deadlock. */
	public static void clientTick(Minecraft mc) {
		if (linked && mc.player != null && lastState != null) {
			// Follow places the player every frame (xo == x), so Minecraft's own walk animation sees no
			// movement. Drive it from the hero's horizontal speed, as LivingEntity does from movement.
			Vec3 v = Proto.toMinecraft(lastState.velocity);
			double blocksPerTick = Math.sqrt(v.x() * v.x() + v.z() * v.z()) / 20.0;
			mc.player.walkAnimation.update((float) Math.min(blocksPerTick * 4.0, 1.0), 0.4f, 1.0f);
		}
		if (mapping == null) return;
		Double3 offset = new Double3(heroAnchor.x() - anchorX, heroAnchor.y() - anchorY, heroAnchor.z() - anchorZ);
		long t0 = System.nanoTime();
		export.tick(mc, mapping, linked && !anchorRequested, offset);
		exportMax = Math.max(exportMax, (System.nanoTime() - t0) / 1e9);
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
		overlay.update(mc, linked);
		if (!linked) return;

		HeroState hero = state.get();
		lastState = hero;
		Vec3 heroBlocks = Proto.toMinecraft(hero.position);
		double localNow = System.nanoTime() / 1e9;
		if (hero.recenterSerial != recenterSerial && !anchorRequested) {
			// The plugin moved the hero and every collider by recenterDelta; move the anchor (and the
			// buffered samples) with them so the player doesn't move at all.
			Vec3 d = Proto.toMinecraft(hero.recenterDelta);
			heroAnchor = new Vec3(heroAnchor.x() + d.x(), heroAnchor.y() + d.y(), heroAnchor.z() + d.z());
			timeline.shift(d.x(), d.y(), d.z());
			LOG.info("Deadcraft: Deadlock recentred by {} blocks; hero now {} (tick {}), player at {}",
				String.format("(%.1f, %.1f, %.1f)", d.x(), d.y(), d.z()), fmt(heroBlocks), hero.tick, fmt(player));
		}
		recenterSerial = hero.recenterSerial;
		if (!anchorRequested && lastDisplacement != null) {
			double jump = Math.sqrt(sq(heroBlocks.x() - heroAnchor.x() - lastDisplacement.x())
				+ sq(heroBlocks.y() - heroAnchor.y() - lastDisplacement.y()) + sq(heroBlocks.z() - heroAnchor.z() - lastDisplacement.z()));
			if (jump > RESPAWN_JUMP_BLOCKS) {
				// A big jump that isn't a recentre: respawn or a Deadlock-side teleport. Keep the player
				// where they are and re-anchor rather than throwing them across the world.
				LOG.info("Deadcraft: hero jumped {} blocks, re-anchoring; hero {} tick {} serial {}, player {}",
					String.format("%.0f", jump), fmt(heroBlocks), hero.tick, hero.recenterSerial, fmt(player));
				anchorRequested = true;
			}
		}
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
			timeline.clear();
		}
		lastDisplacement = new Vec3(heroBlocks.x() - heroAnchor.x(), heroBlocks.y() - heroAnchor.y(), heroBlocks.z() - heroAnchor.z());

		DeadlockCamera.setEyeHeight(hero.eyePosition.z() - hero.position.z());
		readAbilities(hero);
		{
			// Hero model animation: velocity relative to where the body faces.
			Vec3 hv = Proto.toMinecraft(hero.velocity);
			double yawRad = Math.toRadians(player.yBodyRot);
			double fx = -Math.sin(yawRad), fz = Math.cos(yawRad);  // Minecraft facing at this yaw
			double forward = hv.x() * fx + hv.z() * fz, right = -(hv.x() * fz - hv.z() * fx);
			HeroRenderer.setInput(new HeroAnimator.Input(forward, right, hv.y(), (hero.flags & HeroFlags.ON_GROUND) != 0,
				(float) (hero.eyePosition.z() - hero.position.z())));
			speedMax = Math.max(speedMax, Math.hypot(forward, right));
		}
		Vec3 v = Proto.toMinecraft(hero.velocity);
		timeline.add(new HeroTimeline.Sample(hero.tick, hero.serverTime, heroBlocks.x(), heroBlocks.y(), heroBlocks.z(),
			hero.cameraAngles.y(), hero.cameraAngles.x(), v.x(), v.y(), v.z()), localNow);
		if (hero.tick != lastLookTick) {
			lastLookTick = hero.tick;
			look.addSample(timeline.localTime(hero.serverTime), hero.cameraAngles.y(), hero.cameraAngles.x());
		}
		HeroTimeline.Pose pose = timeline.poseAt(localNow);
		// Raw-mouse look while Deadlock has the input; Deadlock's own (delayed) angles otherwise.
		LookPredictor.Look predicted = rawLook && mc.gui.screen() == null ? look.predict(System.nanoTime()) : null;
		double x = anchorX + (pose.x() - heroAnchor.x());
		double y = anchorY + (pose.y() - heroAnchor.y());
		double z = anchorZ + (pose.z() - heroAnchor.z());
		player.setPos(x, y, z);
		// Same old and new position: nothing left for Minecraft to interpolate, we place it every frame.
		player.xo = player.xOld = x;
		player.yo = player.yOld = y;
		player.zo = player.zOld = z;
		player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
		// Grounded only if Minecraft's own blocks agree; claiming ground in mid-air makes the server
		// resend the chunk below ("standing on air"), which flashes nearby buildings.
		// Grounded only if Minecraft's own blocks agree: something solid just under the player's box.
		boolean grounded = (hero.flags & HeroFlags.ON_GROUND) != 0
			&& !mc.level.noCollision(player, player.getBoundingBox().move(0, -0.06, 0));
		player.setOnGround(grounded);
		player.resetFallDistance();

		if (mc.gui.screen() == null) {
			// Source yaw 0 faces +x (Minecraft east, yaw -90) and turns toward +y (Minecraft north).
			if (predicted != null) {
				// How far ahead of Deadlock's (delayed) angle the raw-mouse camera is drawn this frame.
				double lead = Math.abs(LookPredictor.wrap(predicted.yaw() - pose.yaw()));
				leadSum += lead;
				leadMax = Math.max(leadMax, lead);
				leadFrames++;
			}
			float yaw = -90f - (predicted != null ? predicted.yaw() : pose.yaw());
			float pitch = predicted != null ? predicted.pitch() : pose.pitch();
			player.setYRot(yaw);
			player.setXRot(pitch);
			player.yRotO = yaw;
			player.xRotO = pitch;
			player.setYHeadRot(yaw);
			player.yHeadRotO = yaw;
			player.yBodyRot = yaw;
			player.yBodyRotO = yaw;
			// Held-item sway eases toward the look direction once per tick (20 Hz) while we turn every
			// frame, so the hand lags and catches up in steps. Lock it to the camera instead.
			player.xBob = player.xBobO = pitch;
			player.yBob = player.yBobO = yaw;
		}
		frameStats(mc, localNow);
	}

	/** New entries in the plugin's ability ring since the last frame. */
	private static void readAbilities(HeroState hero) {
		int newest = hero.abilityEventSerial;
		if (lastAbilitySerial < 0 || Integer.compareUnsigned(newest, lastAbilitySerial) < 0 || newest - lastAbilitySerial > 64) {
			lastAbilitySerial = newest;  // first frame, plugin restart, or a long gap: start from now
			return;
		}
		for (int serial = lastAbilitySerial + 1; Integer.compareUnsigned(serial, newest) <= 0; serial++) {
			mapping.readAbilityEvent(serial).ifPresent(e -> {
				if (abilitiesSeen.add(e.abilityName)) LOG.info("Deadcraft: ability event {}", e.abilityName);
				HeroRenderer.ability(e.abilityName);
				if (e.abilityName.toLowerCase().contains("dash")) DeadlockCamera.dashKick();
			});
		}
		lastAbilitySerial = newest;
	}

	// ---- frame timing (logged every 10 s while linked) ----------------------------------------

	private static double lastFrame, statsSince, frameMax, frameSum, exportMax, leadSum, leadMax, speedMax;
	private static int leadFrames;
	private static int frames;

	private static void frameStats(Minecraft mc, double now) {
		if (lastFrame > 0) {
			double dt = now - lastFrame;
			frameMax = Math.max(frameMax, dt);
			frameSum += dt;
			frames++;
		}
		lastFrame = now;
		if (statsSince == 0) statsSince = now;
		if (now - statsSince < 10 || frames == 0) return;
		LOG.info("Deadcraft: frames {} avg {} ms ({} fps) worst {} ms; block export worst {} ms; limit {} vsync {}; {}; raw mouse reports {}, ahead of Deadlock's angle by {} deg avg, {} max",
			frames, String.format("%.2f", frameSum / frames * 1000), String.format("%.0f", frames / frameSum),
			String.format("%.1f", frameMax * 1000), String.format("%.1f", exportMax * 1000),
			mc.options.framerateLimit().get(), mc.options.enableVsync().get(), look.describe(), rawMouse.reports(),
			String.format("%.2f", leadFrames == 0 ? 0 : leadSum / leadFrames), String.format("%.1f", leadMax));
		LOG.info("Deadcraft: {}; top speed {} blocks/s", HeroRenderer.stats(), String.format("%.2f", speedMax));
		speedMax = 0;
		statsSince = now;
		frameMax = frameSum = exportMax = leadSum = leadMax = 0;
		leadFrames = 0;
		frames = 0;
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
				mapping = Mapping.openOrCreate(Proto.MAPPING_NAME);
				LOG.info("Deadcraft: bridge opened ({})", Proto.MAPPING_NAME);
			} catch (RuntimeException e) {
				problem("bridge refused: " + e.getMessage());
				return Optional.empty();
			}
		}
		if (mapping.readHeader().deadlockPid == 0) {
			problem("waiting for Deadlock (start the Deadworks server with the Deadcraft plugin)");
			return Optional.empty();
		}
		long age = mapping.deadlockHeartbeatAgeMs();
		if (age > STALE_MS) {
			problem("Deadlock bridge stale (no heartbeat for over " + STALE_MS + " ms)");
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


	private static String fmt(Vec3 v) {
		return String.format("(%.2f, %.2f, %.2f)", v.x(), v.y(), v.z());
	}

	private static String fmt(LocalPlayer p) {
		return String.format("(%.2f, %.2f, %.2f)", p.getX(), p.getY(), p.getZ());
	}

	private static double sq(double v) {
		return v * v;
	}

	private static void setLinked(Minecraft mc, boolean link) {
		linked = link;
		lastDisplacement = null;
		if (link) {
			anchorRequested = true;
			// Deadlock has the keyboard and mouse, so Minecraft must keep running without focus.
			savedPauseOnLostFocus = mc.options.pauseOnLostFocus;
			mc.options.pauseOnLostFocus = false;
			// Minecraft's walk bob is computed from position changes we overwrite every frame, so it
			// twitches the hand and camera; Deadlock's camera doesn't bob like that anyway.
			savedBobView = mc.options.bobView().get();
			mc.options.bobView().set(false);
			timeline.clear();
			var monitor = mc.getWindow().findBestMonitor();
			LOG.info("Deadcraft: monitor {} Hz, frame limit {}, vsync {}", monitor == null ? "?" : monitor.currentMode().getRefreshRate(),
				mc.options.framerateLimit().get(), mc.options.enableVsync().get());
			DeadlockCamera.onLink(mc, true);
			look.clear();
			lastLookTick = -1;
			lastAbilitySerial = -1;
			if (rawLook) rawMouse.start();
			lastProblem = "";
			status(mc, "Deadcraft: following the Deadlock hero");
		} else {
			mc.options.pauseOnLostFocus = savedPauseOnLostFocus;
			mc.options.bobView().set(savedBobView);
			DeadlockCamera.onLink(mc, false);
			rawMouse.stop();
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

	static String camera(float distance, float right, float up) {
		return DeadlockCamera.set(distance, right, up);
	}

	static String camera(Boolean on) {
		return on == null ? DeadlockCamera.describe() : DeadlockCamera.setEnabled(Minecraft.getInstance(), on, linked);
	}

	static String look(Boolean raw) {
		if (raw != null) {
			rawLook = raw;
			if (raw && linked) rawMouse.start();
			if (!raw) rawMouse.stop();
		}
		if (!rawLook) return "look: Deadlock's angles (smoothed, delayed). /deadcraft look raw to turn with the mouse.";
		String problem = rawMouse.problem();
		return look.describe() + (problem.isEmpty() ? "" : " [" + problem + "]");
	}

	static String lookRecalibrate() {
		look.reset();
		return "look: calibration cleared; move the mouse around in Deadlock for a few seconds.";
	}

	static String delay(Float ms) {
		if (ms != null) timeline.setDelay(ms / 1000.0);
		return String.format("position delay %.0f ms behind Deadlock's newest sample (default %.0f).", timeline.delay() * 1000,
			HeroTimeline.DELAY_S * 1000);
	}

	static String hero(String name) {
		return HeroRenderer.command(name);
	}

	static String setOverlay(boolean on) {
		return overlay.setEnabled(on);
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
