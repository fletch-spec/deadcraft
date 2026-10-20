package dev.deadcraft.client.fx;

import java.util.function.Supplier;
import net.minecraft.world.phys.Vec3;

/**
 * A strip of light between two points, turned to face the camera about its own line: a pillar from the sky, or
 * (steady, between moving points) the beam from the horn to the orb. A timed beam narrows and fades over its
 * duration; a steady one ({@code duration < 0}) holds until stopped or an end goes away, then fades quickly. The
 * texture runs down V, across U.
 */
public final class Beam implements Vfx {
	private static final float FADE_S = 0.1f;
	private final Supplier<Vec3> from, to;
	private final String texture;
	private final float width, duration, alpha, brightness;
	private final float[] color;
	private Vec3 a, b;
	private float time, fadeOut = -1;
	private boolean fading;
	private final float[] xyz = new float[12], uv = {0, 0, 1, 0, 1, 1, 0, 1};
	private final int[] argb = new int[4];

	public Beam(Vec3 from, Vec3 to, String texture, float width, float duration, float[] color, float alpha, float brightness) {
		this(() -> from, () -> to, texture, width, duration, color, alpha, brightness);
	}

	public Beam(Supplier<Vec3> from, Supplier<Vec3> to, String texture, float width, float duration, float[] color, float alpha, float brightness) {
		this.from = from;
		this.to = to;
		this.texture = texture;
		this.width = width;
		this.duration = duration;
		this.color = color;
		this.alpha = alpha;
		this.brightness = brightness;
	}

	@Override
	public boolean tick(float dt) {
		time += dt;
		if (fading) {
			fadeOut -= dt;
			return fadeOut > 0;
		}
		Vec3 na = from.get(), nb = to.get();
		if (na == null || nb == null) {
			stop();
			return a != null;
		}
		a = na;
		b = nb;
		return duration < 0 || time < duration;
	}

	@Override
	public void stop() {
		// Once only: a group keeps stepping parts that have finished.
		if (!fading) {
			fading = true;
			fadeOut = FADE_S;
		}
	}

	@Override
	public void draw(Vec3 eye, Vec3 right, Vec3 up, Out out) {
		if (a == null) return;
		float t = duration > 0 ? Math.min(1, time / duration) : 0;
		float fade = fading ? Math.max(0, fadeOut) / FADE_S : 1;
		float w = width * (1 - t * t);
		float al = alpha * (1 - t) * fade;
		if (w <= 0 || al <= 0.002f) return;
		Vec3 line = b.subtract(a);
		Vec3 mid = a.add(line.scale(0.5));
		Vec3 side = line.cross(mid.subtract(eye));
		if (side.lengthSqr() < 1e-10) return;
		side = side.normalize().scale(w);
		put(0, a.subtract(side), eye);
		put(1, a.add(side), eye);
		put(2, b.add(side), eye);
		put(3, b.subtract(side), eye);
		int col = Vfx.argb(color[0], color[1], color[2], al);
		argb[0] = argb[1] = argb[2] = argb[3] = col;
		out.quad(texture, true, xyz, uv, argb, brightness);
	}

	private void put(int k, Vec3 p, Vec3 eye) {
		xyz[k * 3] = (float) (p.x - eye.x);
		xyz[k * 3 + 1] = (float) (p.y - eye.y);
		xyz[k * 3 + 2] = (float) (p.z - eye.z);
	}
}
