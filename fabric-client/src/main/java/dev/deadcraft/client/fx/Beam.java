package dev.deadcraft.client.fx;

import net.minecraft.world.phys.Vec3;

/**
 * A strip of light between two fixed points, turned to face the camera about its own line (a pillar from the
 * sky): full width at once, then narrowing and fading over its duration. The texture runs down V, across U.
 */
public final class Beam implements Vfx {
	private final Vec3 from, to;
	private final String texture;
	private final float width, duration, alpha, brightness;
	private final float[] color;
	private float time;
	private final float[] xyz = new float[12], uv = {0, 0, 1, 0, 1, 1, 0, 1};
	private final int[] argb = new int[4];

	public Beam(Vec3 from, Vec3 to, String texture, float width, float duration, float[] color, float alpha, float brightness) {
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
		return time < duration;
	}

	@Override
	public void draw(Vec3 eye, Vec3 right, Vec3 up, Out out) {
		float t = Math.min(1, time / duration);
		float w = width * (1 - t * t);
		float a = alpha * (1 - t);
		if (w <= 0 || a <= 0.002f) return;
		Vec3 line = to.subtract(from);
		Vec3 mid = from.add(line.scale(0.5));
		Vec3 side = line.cross(mid.subtract(eye));
		if (side.lengthSqr() < 1e-10) return;
		side = side.normalize().scale(w);
		put(0, from.subtract(side), eye);
		put(1, from.add(side), eye);
		put(2, to.add(side), eye);
		put(3, to.subtract(side), eye);
		int col = Vfx.argb(color[0], color[1], color[2], a);
		argb[0] = argb[1] = argb[2] = argb[3] = col;
		out.quad(texture, true, xyz, uv, argb, brightness);
	}

	private void put(int k, Vec3 p, Vec3 eye) {
		xyz[k * 3] = (float) (p.x - eye.x);
		xyz[k * 3 + 1] = (float) (p.y - eye.y);
		xyz[k * 3 + 2] = (float) (p.z - eye.z);
	}

	@Override
	public void stop() {
	}
}
