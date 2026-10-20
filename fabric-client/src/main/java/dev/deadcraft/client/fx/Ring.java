package dev.deadcraft.client.fx;

import net.minecraft.world.phys.Vec3;

/**
 * A flat card lying in the plane across {@code normal} (not facing the camera): a ring or flash on a surface
 * that grows from {@code startSize} to {@code endSize} and fades over its duration. Impacts, jump rings,
 * shockwaves.
 */
public final class Ring implements Vfx {
	private final Vec3 at, a, b;
	private final String texture;
	private final float startSize, endSize, duration, alpha, brightness;
	private final float[] color;
	private final boolean additive;
	private float time;
	/** The fraction of its life it stays full before fading. */
	private float hold = 0.4f;
	private final float[] xyz = new float[12];
	private final int[] argb = new int[4];

	public Ring(Vec3 at, Vec3 normal, String texture, float startSize, float endSize, float duration, float[] color, float alpha, float brightness,
		boolean additive) {
		this.at = at;
		Vec3 n = normal.normalize();
		// Two axes across the normal.
		Vec3 ref = Math.abs(n.y) < 0.9 ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0);
		this.a = n.cross(ref).normalize();
		this.b = n.cross(a).normalize();
		this.texture = texture;
		this.startSize = startSize;
		this.endSize = endSize;
		this.duration = duration;
		this.color = color;
		this.alpha = alpha;
		this.brightness = brightness;
		this.additive = additive;
	}

	@Override
	public boolean tick(float dt) {
		time += dt;
		return time < duration;
	}

	@Override
	public void draw(Vec3 eye, Vec3 right, Vec3 up, Out out) {
		float t = Math.min(1, time / duration);
		// Grows fast then slows (ease out); fades over the last part.
		float grow = 1 - (1 - t) * (1 - t);
		float s = startSize + (endSize - startSize) * grow;
		float fade = t < hold ? 1 : 1 - (t - hold) / (1 - hold);
		float al = alpha * fade;
		if (s <= 0 || al <= 0.002f) return;
		double cx = at.x - eye.x, cy = at.y - eye.y, cz = at.z - eye.z;
		for (int k = 0; k < 4; k++) {
			int sa = k == 0 || k == 3 ? -1 : 1, sb = k < 2 ? -1 : 1;
			xyz[k * 3] = (float) (cx + (a.x * sa + b.x * sb) * s);
			xyz[k * 3 + 1] = (float) (cy + (a.y * sa + b.y * sb) * s);
			xyz[k * 3 + 2] = (float) (cz + (a.z * sa + b.z * sb) * s);
		}
		int col = Vfx.argb(color[0], color[1], color[2], al);
		argb[0] = argb[1] = argb[2] = argb[3] = col;
		out.quad(texture, additive, xyz, Glow.UV, argb, brightness);
	}

	/** Stays full for this fraction of its life before fading (0.4 by default). */
	public Ring hold(float fraction) {
		hold = Math.max(0, Math.min(0.99f, fraction));
		return this;
	}

	@Override
	public void stop() {
	}
}
