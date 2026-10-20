package dev.deadcraft.client.fx;

import java.util.function.Supplier;
import net.minecraft.world.phys.Vec3;

/**
 * A glow card held on a point (a flame's hot core and halo): flickering a little, and either steady until
 * stopped or flaring and fading over a set time (a flash). Fades out quickly once stopped.
 */
public final class Glow implements Vfx {
	private final Supplier<Vec3> anchor;
	private final String texture;
	private final float size, brightness, flicker;
	private final float[] color;
	private final float alpha;
	private final boolean additive;
	/** Seconds to fade in and out over (a flash), or below 0 for steady until stopped. */
	private final float duration;
	/** Size at the end of a flash, as a fraction of its start. */
	private final float endSize;
	/** A steady glow grows to {@link #endSize} over this many seconds (a charge), when above 0. */
	private float charge;
	private Vec3 at;
	private float time, fadeOut = -1;
	private final float phase;
	private static final float FADE_S = 0.08f;
	private static final int[][] SIGNS = {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}};
	static final float[] UV = {0, 1, 1, 1, 1, 0, 0, 0};
	private final float[] xyz = new float[12];
	private final int[] argb = new int[4];

	public Glow(Supplier<Vec3> anchor, String texture, float size, float[] color, float alpha, float brightness, float flicker, boolean additive,
		float duration, float endSize) {
		this.anchor = anchor;
		this.texture = texture;
		this.size = size;
		this.color = color;
		this.alpha = alpha;
		this.brightness = brightness;
		this.flicker = flicker;
		this.additive = additive;
		this.duration = duration;
		this.endSize = endSize;
		this.phase = (float) (Math.random() * 100);
	}

	/** A steady glow until stopped. */
	public static Glow steady(Supplier<Vec3> anchor, String texture, float size, float[] color, float alpha, float brightness, float flicker) {
		return new Glow(anchor, texture, size, color, alpha, brightness, flicker, true, -1, 1);
	}

	/** A steady glow that grows to {@code endSize} of its size over {@code chargeS} seconds (the orb charging). */
	public static Glow charging(Supplier<Vec3> anchor, String texture, float size, float endSize, float chargeS, float[] color, float alpha,
		float brightness, float flicker) {
		Glow g = new Glow(anchor, texture, size, color, alpha, brightness, flicker, true, -1, endSize);
		g.charge = chargeS;
		return g;
	}

	/** A flash that follows a moving point (a glyph on the body). */
	public static Glow flash(Supplier<Vec3> anchor, String texture, float size, float[] color, float alpha, float brightness, float duration,
		float endSize) {
		return new Glow(anchor, texture, size, color, alpha, brightness, 0, true, duration, endSize);
	}

	/** A flash: full at once, fading (and growing or shrinking to {@code endSize} of its size) over {@code duration}. */
	public static Glow flash(Vec3 at, String texture, float size, float[] color, float alpha, float brightness, float duration, float endSize) {
		return new Glow(() -> at, texture, size, color, alpha, brightness, 0, true, duration, endSize);
	}

	@Override
	public void stop() {
		if (fadeOut < 0) fadeOut = FADE_S;
	}

	@Override
	public boolean tick(float dt) {
		time += dt;
		if (fadeOut >= 0) {
			fadeOut -= dt;
			return fadeOut > 0;
		}
		Vec3 now = anchor.get();
		if (now == null) {
			stop();
			return true;
		}
		at = now;
		return duration < 0 || time < duration;
	}

	@Override
	public void draw(Vec3 eye, Vec3 right, Vec3 up, Out out) {
		if (at == null) return;
		float t = duration > 0 ? Math.min(1, time / duration) : charge > 0 ? Math.min(1, time / charge) : 0;
		float a = alpha * (duration > 0 ? (1 - t) * (1 - t) : 1) * (fadeOut >= 0 ? fadeOut / FADE_S : 1);
		// Two out-of-step waves: an uneven flicker.
		float f = 1 + flicker * (float) (0.6 * Math.sin(time * 23 + phase) + 0.4 * Math.sin(time * 37.7 + phase * 2));
		float s = size * f * (1 + (endSize - 1) * t);
		if (a <= 0.002f || s <= 0) return;
		double cx = at.x - eye.x, cy = at.y - eye.y, cz = at.z - eye.z;
		for (int k = 0; k < 4; k++) {
			xyz[k * 3] = (float) (cx + right.x * s * SIGNS[k][0] + up.x * s * SIGNS[k][1]);
			xyz[k * 3 + 1] = (float) (cy + right.y * s * SIGNS[k][0] + up.y * s * SIGNS[k][1]);
			xyz[k * 3 + 2] = (float) (cz + right.z * s * SIGNS[k][0] + up.z * s * SIGNS[k][1]);
		}
		int col = Vfx.argb(color[0], color[1], color[2], a);
		argb[0] = argb[1] = argb[2] = argb[3] = col;
		out.quad(texture, additive, xyz, UV, argb, brightness * f);
	}
}
