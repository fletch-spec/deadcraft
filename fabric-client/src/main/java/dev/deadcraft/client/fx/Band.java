package dev.deadcraft.client.fx;

import java.util.function.Supplier;
import net.minecraft.world.phys.Vec3;

/**
 * An upright band of light round a centre (a wall ring): all the way round, or an arc of it facing one way. Its
 * radius and height above the centre move from start to end values (easing out) over the first part of its
 * life, and it fades over the last part. Luminous Strike's rim dropping onto the ground, Radiant Blast's arc.
 * The texture's bright line (a beam texture runs down V) runs along the band.
 */
public final class Band implements Vfx {
	private static final int SEGMENTS = 40;
	private final Supplier<Vec3> centre;
	private Vec3 at;
	/** Radians a second the arc turns round its centre. */
	private float spin;
	private final float r0, r1, y0, y1, height, moveS, duration, alpha, brightness;
	/** The arc's middle (radians round from +x towards +z) and half its angle (PI: all the way round). */
	private final double facing, half;
	private final String texture;
	private final float[] color;
	private final boolean additive;
	private float time;
	private final float[] xyz = new float[12], uv = new float[8];
	private final int[] argb = new int[4];

	public Band(Vec3 centre, float r0, float r1, float y0, float y1, float height, float moveS, float duration, double facing, double half,
		String texture, float[] color, float alpha, float brightness, boolean additive) {
		this(() -> centre, r0, r1, y0, y1, height, moveS, duration, facing, half, texture, color, alpha, brightness, additive);
	}

	/** A band round a moving centre (round her body); it ends early if the centre goes away. */
	public Band(Supplier<Vec3> centre, float r0, float r1, float y0, float y1, float height, float moveS, float duration, double facing, double half,
		String texture, float[] color, float alpha, float brightness, boolean additive) {
		this.centre = centre;
		this.r0 = r0;
		this.r1 = r1;
		this.y0 = y0;
		this.y1 = y1;
		this.height = height;
		this.moveS = moveS;
		this.duration = duration;
		this.facing = facing;
		this.half = half;
		this.texture = texture;
		this.color = color;
		this.alpha = alpha;
		this.brightness = brightness;
		this.additive = additive;
	}

	/** Turns the arc round its centre, radians a second. */
	public Band spin(float radiansPerSecond) {
		spin = radiansPerSecond;
		return this;
	}

	@Override
	public boolean tick(float dt) {
		time += dt;
		Vec3 c = centre.get();
		if (c == null) return false;
		at = c;
		return time < duration;
	}

	@Override
	public void draw(Vec3 eye, Vec3 right, Vec3 up, Out out) {
		float m = moveS <= 0 ? 1 : Math.min(1, time / moveS);
		float ease = 1 - (1 - m) * (1 - m);
		float r = r0 + (r1 - r0) * ease, y = y0 + (y1 - y0) * ease;
		float t = time / duration;
		float a = alpha * (t < 0.75f ? 1 : (1 - t) / 0.25f);
		if (a <= 0.002f || r <= 0 || at == null) return;
		double cx = at.x - eye.x, cy = at.y - eye.y, cz = at.z - eye.z;
		double facing = this.facing + spin * time;
		// Partial arcs fade at their ends.
		boolean full = half >= Math.PI - 1e-3;
		for (int i = 0; i < SEGMENTS; i++) {
			double fa = (double) i / SEGMENTS, fb = (double) (i + 1) / SEGMENTS;
			double aa = facing - half + 2 * half * fa, ab = facing - half + 2 * half * fb;
			float ea = full ? 1 : edge(fa), eb = full ? 1 : edge(fb);
			float xa = (float) (cx + Math.cos(aa) * r), za = (float) (cz + Math.sin(aa) * r);
			float xb = (float) (cx + Math.cos(ab) * r), zb = (float) (cz + Math.sin(ab) * r);
			float bottom = (float) (cy + y), top = bottom + height;
			set(0, xa, bottom, za);
			set(1, xb, bottom, zb);
			set(2, xb, top, zb);
			set(3, xa, top, za);
			// Across the band is the texture's U, along it V.
			uv[0] = 0; uv[1] = (float) fa;
			uv[2] = 0; uv[3] = (float) fb;
			uv[4] = 1; uv[5] = (float) fb;
			uv[6] = 1; uv[7] = (float) fa;
			int ca = Vfx.argb(color[0], color[1], color[2], a * ea), cb = Vfx.argb(color[0], color[1], color[2], a * eb);
			argb[0] = argb[3] = ca;
			argb[1] = argb[2] = cb;
			out.quad(texture, additive, xyz, uv, argb, brightness);
		}
	}

	/** 0 at an arc's ends, 1 through its middle. */
	private static float edge(double f) {
		return (float) Math.min(1, Math.min(f, 1 - f) * 5);
	}

	private void set(int k, float x, float y, float z) {
		xyz[k * 3] = x;
		xyz[k * 3 + 1] = y;
		xyz[k * 3 + 2] = z;
	}

	@Override
	public void stop() {
	}
}
