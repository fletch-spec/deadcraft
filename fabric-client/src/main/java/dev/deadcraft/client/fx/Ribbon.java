package dev.deadcraft.client.fx;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.function.Supplier;
import net.minecraft.world.phys.Vec3;

/**
 * A trail behind a moving point (a bullet's tracer): the points it passed in the last {@code length} seconds,
 * drawn as a strip facing the camera, full at the head and tapering and fading to the tail. Once stopped it
 * shrinks into its head as its points age out.
 */
public final class Ribbon implements Vfx {
	private final Supplier<Vec3> anchor;
	private final String texture;
	private final float width, length, brightness;
	private final float[] head, tail;
	private final float alpha;
	private final ArrayDeque<Vec3> points = new ArrayDeque<>();
	private final ArrayDeque<Float> times = new ArrayDeque<>();
	private float time;
	private boolean stopped;

	public Ribbon(Supplier<Vec3> anchor, String texture, float width, float length, float[] head, float[] tail, float alpha, float brightness) {
		this.anchor = anchor;
		this.texture = texture;
		this.width = width;
		this.length = length;
		this.head = head;
		this.tail = tail;
		this.alpha = alpha;
		this.brightness = brightness;
	}

	@Override
	public void stop() {
		stopped = true;
	}

	@Override
	public boolean tick(float dt) {
		time += dt;
		Vec3 now = stopped ? null : anchor.get();
		if (now == null) stopped = true;
		else {
			points.addFirst(now);
			times.addFirst(time);
		}
		while (!times.isEmpty() && time - times.peekLast() > length) {
			points.removeLast();
			times.removeLast();
		}
		return !stopped || points.size() > 1;
	}

	@Override
	public void draw(Vec3 eye, Vec3 right, Vec3 up, Out out) {
		if (points.size() < 2) return;
		Vec3 view = up.cross(right);
		Iterator<Vec3> pi = points.iterator();
		Iterator<Float> ti = times.iterator();
		Vec3 a = pi.next();
		float ta = ti.next();
		float[] xyz = new float[12], uv = new float[8];
		int[] argb = new int[4];
		while (pi.hasNext()) {
			Vec3 b = pi.next();
			float tb = ti.next();
			// 0 at the head, 1 at the tail (by age).
			float fa = Math.min(1, (time - ta) / length), fb = Math.min(1, (time - tb) / length);
			Vec3 seg = b.subtract(a);
			if (seg.lengthSqr() > 1e-10) {
				Vec3 side = seg.cross(view);
				if (side.lengthSqr() > 1e-12) {
					side = side.normalize();
					Vec3 sa = side.scale(width * (1 - fa * 0.7f)), sb = side.scale(width * (1 - fb * 0.7f));
					put(xyz, 0, a.subtract(sa), eye);
					put(xyz, 1, a.add(sa), eye);
					put(xyz, 2, b.add(sb), eye);
					put(xyz, 3, b.subtract(sb), eye);
					// Deadlock's beam textures run down V; U is across.
					uv[0] = 0; uv[1] = fa;
					uv[2] = 1; uv[3] = fa;
					uv[4] = 1; uv[5] = fb;
					uv[6] = 0; uv[7] = fb;
					argb[0] = argb[1] = color(fa);
					argb[2] = argb[3] = color(fb);
					out.quad(texture, true, xyz, uv, argb, brightness);
				}
			}
			a = b;
			ta = tb;
		}
	}

	private int color(float f) {
		float a = alpha * (1 - f) * (1 - f);
		return Vfx.argb(head[0] + (tail[0] - head[0]) * f, head[1] + (tail[1] - head[1]) * f, head[2] + (tail[2] - head[2]) * f, a);
	}

	private static void put(float[] xyz, int k, Vec3 p, Vec3 eye) {
		xyz[k * 3] = (float) (p.x - eye.x);
		xyz[k * 3 + 1] = (float) (p.y - eye.y);
		xyz[k * 3 + 2] = (float) (p.z - eye.z);
	}
}
