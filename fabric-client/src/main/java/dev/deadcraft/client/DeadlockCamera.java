package dev.deadcraft.client;

import dev.deadcraft.protocol.Proto;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;

/**
 * Settings for the Deadlock-style third-person camera ({@code CameraMixin}). Distances are Source
 * units, converted with the protocol's units per block. Deadlock's defaults per Deadworks'
 * CameraSetting: distance 150, FOV 90 (horizontal). The shoulder offsets are starting values to tune by
 * eye with {@code /deadcraft camera}, until Minecraft's crosshair sits where Deadlock's would.
 */
public final class DeadlockCamera {
	private static boolean enabled = true;
	private static float distance = 180f, right = 40f, up = 15f;  // tuned by eye with the hero model at scale 1.2, 2026-10-10
	/** Deadlock's eye height above the feet, from the hero state (86 units for Celeste). */
	private static float eyeHeight = 86f;
	private static CameraType savedType;
	private static int savedFov = -1;

	private DeadlockCamera() {}

	/**
	 * Degrees added to the camera's pitch (negative: up). None: Deadlock launches shots 2 deg above its
	 * crosshair so their drop brings them back onto it at range (Celeste's at about 26 blocks); tilting the
	 * camera to the launch angle put every hit below the crosshair.
	 */
	private static final float AIM_PITCH_OFFSET = 0f;

	public static float aimPitchOffset() {
		return AIM_PITCH_OFFSET;
	}

	/**
	 * Learned from the hero's shots: how far right and up of the camera's ray the shots' line runs, blocks.
	 * The camera moves by it so its ray (the crosshair) lies on that line and shots land on the crosshair
	 * at every distance. Deadlock's shots leave from the weapon, below where this camera sits.
	 */
	private static float aimRight, aimUp;
	private static final float AIM_SHIFT_MAX = 0.75f;
	private static int aimShots;

	static void learnShotLine(double rightBlocks, double upBlocks) {
		// Each shot's line is measured from the camera as already shifted: move half the remaining way.
		aimRight = Math.max(-AIM_SHIFT_MAX, Math.min(AIM_SHIFT_MAX, aimRight + (float) rightBlocks * 0.5f));
		aimUp = Math.max(-AIM_SHIFT_MAX, Math.min(AIM_SHIFT_MAX, aimUp + (float) upBlocks * 0.5f));
		if (++aimShots % 10 == 0 || aimShots <= 3) {
			Follow.LOG.info("Deadcraft: camera moved onto the shots' line: {} right, {} up (Source units); last shot's line {} right, {} up of the ray",
				String.format("%.1f", aimRight * 64), String.format("%.1f", aimUp * 64), String.format("%.2f", rightBlocks * 64), String.format("%.2f", upBlocks * 64));
		}
	}

	public static boolean enabled() {
		return enabled;
	}

	public static float distanceBlocks() {
		return distance / Proto.UNITS_PER_BLOCK;
	}

	public static float aimRightBlocks() {
		return aimRight;
	}

	public static float aimUpBlocks() {
		return aimUp;
	}

	public static float rightBlocks() {
		return right / Proto.UNITS_PER_BLOCK;
	}

	public static float upBlocks() {
		return up / Proto.UNITS_PER_BLOCK;
	}

	public static float eyeHeightBlocks() {
		return eyeHeight / Proto.UNITS_PER_BLOCK;
	}

	/** Deadlock eases its camera when the eye height changes (crouch); so do we. */
	private static final double EYE_EASE_S = 0.1;
	private static float standingEye = 86f;
	private static double lastEyeTime;

	/**
	 * Every linked frame: Deadlock's eye height for the hero (snaps on crouch), and whether the hero is
	 * sliding (Deadlock keeps the eye up but its camera drops to crouch height for a slide).
	 */
	static void setEyeHeight(float units, boolean sliding, double now) {
		if (units <= 10f || units >= 400f) return;
		standingEye = Math.max(standingEye, units);
		float target = sliding ? Math.min(units, standingEye * 0.64f) : units;  // crouch is 55 of 86 for Celeste
		double dt = lastEyeTime == 0 ? 1 : Math.min(1, now - lastEyeTime);
		lastEyeTime = now;
		eyeHeight += (float) ((target - eyeHeight) * (1 - Math.exp(-dt / EYE_EASE_S)));
	}

	/** On link: third person (so the body is drawn) and Deadlock's field of view. Undone on unlink. */
	static void onLink(Minecraft mc, boolean linked) {
		if (linked && enabled) {
			savedType = mc.options.getCameraType();
			mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
			savedFov = mc.options.fov().get();
			mc.options.fov().set(matchDeadlockFov(mc));
		} else if (savedType != null) {
			mc.options.setCameraType(savedType);
			savedType = null;
			if (savedFov > 0) mc.options.fov().set(savedFov);
			savedFov = -1;
		}
	}

	/** Minecraft's FOV option is vertical; Deadlock's 90 is horizontal. Convert for this window's shape. */
	private static int matchDeadlockFov(Minecraft mc) {
		double aspect = (double) mc.getWindow().getWidth() / Math.max(1, mc.getWindow().getHeight());
		double vertical = Math.toDegrees(2 * Math.atan(Math.tan(Math.toRadians(90) / 2) / aspect));
		return (int) Math.round(Math.max(30, Math.min(110, vertical)));
	}

	// ---- dash FOV kick: Deadlock widens its view for a moment when the hero dashes ----
	/** Peak widening, as a factor on the field of view. Tuned by eye; not read from Deadlock. */
	private static final float KICK = 0.12f, KICK_RISE_S = 0.06f, KICK_FALL_S = 0.35f;
	private static long kickStart = Long.MIN_VALUE;

	static void dashKick() {
		kickStart = System.nanoTime();
	}

	/** The factor to apply to Minecraft's field of view this frame. */
	public static float fovScale() {
		if (!enabled || kickStart == Long.MIN_VALUE) return 1f;
		float t = (System.nanoTime() - kickStart) / 1e9f;
		if (t >= KICK_RISE_S + KICK_FALL_S) return 1f;
		float x = t < KICK_RISE_S ? t / KICK_RISE_S : 1 - (t - KICK_RISE_S) / KICK_FALL_S;
		return 1f + KICK * x * x * (3 - 2 * x);
	}

	static String set(float newDistance, float newRight, float newUp) {
		distance = newDistance;
		right = newRight;
		up = newUp;
		return describe();
	}

	static String setEnabled(Minecraft mc, boolean on, boolean linked) {
		if (on == enabled) return describe();
		if (linked) onLink(mc, false);
		enabled = on;
		if (linked) onLink(mc, true);
		return describe();
	}

	static String describe() {
		return enabled
			? String.format("Deadlock camera: distance %.0f, right %.0f, up %.0f units (eye %.0f). /deadcraft camera <distance> <right> <up>", distance, right, up, eyeHeight)
			: "Deadlock camera off: Minecraft's own camera.";
	}
}
