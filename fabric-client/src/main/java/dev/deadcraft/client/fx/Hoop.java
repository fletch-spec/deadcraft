package dev.deadcraft.client.fx;

import net.minecraft.world.phys.Vec3;

/**
 * A short tube of light round any axis (a ring with depth): its radius grows from start to end (easing out) and
 * it fades over the last part of its life. The air dash's rings, which read as flat cards (round 11). The
 * texture's bright line (a beam texture runs down V) runs round the hoop.
 */
public final class Hoop implements Vfx {
	private static final int SEGMENTS = 32;
	private final Vec3 centre, axis, a, b;
	private final float r0, r1, depth, duration, alpha, brightness;
	private final String texture;
	private final float[] color;
	private final boolean additive;
	private float time;
	private final float[] xyz = new float[12], uv = new float[8];
	private final int[] argb = new int[4];

	public Hoop(Vec3 centre, Vec3 axis, float r0, float r1, float depth, float duration, String texture, float[] color, float alpha,
		float brightness, boolean additive) {
		this.centre = centre;
		this.axis = axis.normalize();
		Vec3 ref = Math.abs(this.axis.y) < 0.9 ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0);
		this.a = this.axis.cross(ref).normalize();
		this.b = this.axis.cross(a).normalize();
		this.r0 = r0;
		this.r1 = r1;
		this.depth = depth;
		this.duration = duration;
		this.texture = texture;
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
		float r = r0 + (r1 - r0) * (1 - (1 - t) * (1 - t));
		float al = alpha * (t < 0.5f ? 1 : (1 - t) / 0.5f);
		if (al <= 0.002f || r <= 0) return;
		Vec3 front = axis.scale(depth / 2);
		int col = Vfx.argb(color[0], color[1], color[2], al);
		argb[0] = argb[1] = argb[2] = argb[3] = col;
		for (int i = 0; i < SEGMENTS; i++) {
			double fa = (double) i / SEGMENTS, fb = (double) (i + 1) / SEGMENTS;
			Vec3 pa = centre.add(a.scale(Math.cos(fa * 2 * Math.PI) * r)).add(b.scale(Math.sin(fa * 2 * Math.PI) * r));
			Vec3 pb = centre.add(a.scale(Math.cos(fb * 2 * Math.PI) * r)).add(b.scale(Math.sin(fb * 2 * Math.PI) * r));
			put(0, pa.subtract(front), eye);
			put(1, pb.subtract(front), eye);
			put(2, pb.add(front), eye);
			put(3, pa.add(front), eye);
			// Across the tube (along its axis) is the texture's U, round it V.
			uv[0] = 0; uv[1] = (float) fa;
			uv[2] = 0; uv[3] = (float) fb;
			uv[4] = 1; uv[5] = (float) fb;
			uv[6] = 1; uv[7] = (float) fa;
			out.quad(texture, additive, xyz, uv, argb, brightness);
		}
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
