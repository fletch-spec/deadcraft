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
	private Vec3 at;
	private float time, fadeOut = -1;
	private final float phase;

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

	/** A flash: full at once, fading (and growing or shrinking to {@code endSize} of its size) over {@code duration}. */
	public static Glow flash(Vec3 at, String texture, float size, float[] color, float alpha, float brightness, float duration, float endSize) {
		return new Glow(() -> at, texture, size, color, alpha, brightness, 0, true, duration, endSize);
	}

	@Override
	public void stop() {
		if (fadeOut < 0) fadeOut = 0.08f;
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
		float t = duration > 0 ? Math.min(1, time / duration) : 0;
		float a = alpha * (duration > 0 ? (1 - t) * (1 - t) : 1) * (fadeOut >= 0 ? fadeOut / 0.08f : 1);
		// Two out-of-step waves: an uneven flicker.
		float f = 1 + flicker * (float) (0.6 * Math.sin(time * 23 + phase) + 0.4 * Math.sin(time * 37.7 + phase * 2));
		float s = size * f * (1 + (endSize - 1) * t);
		if (a <= 0.002f || s <= 0) return;
		double cx = at.x - eye.x, cy = at.y - eye.y, cz = at.z - eye.z;
		float[] xyz = new float[12];
		int[][] signs = {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}};
		for (int k = 0; k < 4; k++) {
			xyz[k * 3] = (float) (cx + right.x * s * signs[k][0] + up.x * s * signs[k][1]);
			xyz[k * 3 + 1] = (float) (cy + right.y * s * signs[k][0] + up.y * s * signs[k][1]);
			xyz[k * 3 + 2] = (float) (cz + right.z * s * signs[k][0] + up.z * s * signs[k][1]);
		}
		int col = Vfx.argb(color[0], color[1], color[2], a);
		out.quad(texture, additive, xyz, new float[] {0, 1, 1, 1, 1, 0, 0, 0}, new int[] {col, col, col, col}, brightness * f);
	}
}
