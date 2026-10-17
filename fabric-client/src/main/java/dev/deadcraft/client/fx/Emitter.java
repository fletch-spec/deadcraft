package dev.deadcraft.client.fx;

import java.util.Random;
import java.util.function.Supplier;
import net.minecraft.world.phys.Vec3;

/**
 * Hand-tuned particles: emitted at an anchor (a point that may move: the wand's end), each living a while,
 * moving, growing or shrinking, changing colour and fading. Parts spawned while the anchor moves are spread
 * along its path, so a swung wand leaves a continuous trail.
 */
public final class Emitter implements Vfx {
	/** How a card faces the camera. */
	public enum Orient {
		/** A square card facing the camera, turned by its roll. */
		FACING,
		/** Stretched along its velocity (or world up while still): a flame tongue. */
		UP,
		/** Stretched along its velocity by its speed: a spark streak. */
		STREAK
	}

	/** An emitter's settings (sizes in blocks, half-widths; speeds in blocks/s; colours linear 0..1). */
	public static final class Spec {
		String texture = Renderers.SOFT_DOT;
		/** Sprite sheet sequence, or -1 for a random one per part. */
		int sequence = -1;
		/** Sheet frames per second; 0 holds a random frame. */
		float fps;
		boolean additive = true;
		float brightness = 1;
		/** Parts per second while emitting, and how many at once at the start. */
		float rate;
		int burst;
		/** Seconds of emitting; below 0, until stopped. */
		float duration = -1;
		float lifeMin = 0.3f, lifeMax = 0.3f;
		float sizeStart = 0.05f, sizeEnd = 0.05f, sizeJitter;
		/** Length over width for flame tongues (UP), and streak length per unit of speed (STREAK, seconds). */
		float stretch = 1.6f;
		float[] colorStart = {1, 1, 1}, colorMid, colorEnd = {1, 1, 1};
		float alpha = 1, fadeIn = 0.1f, fadeOut = 0.5f;
		float spawnRadius;
		/** Starting velocity in the world (or in the anchor's frame: forward, left, up, when it has one). */
		Vec3 velocity = Vec3.ZERO;
		float velocityRandom;
		/** Blocks/s/s, down; and the fraction of speed lost per second. */
		float gravity, drag;
		/** How much of the anchor's own velocity parts start with. */
		float inherit;
		Orient orient = Orient.FACING;
		/** Random starting roll (radians, either way) and spin (radians/s, either way). */
		float roll, spin;

		Spec texture(String t) { texture = t; return this; }
		Spec sequence(int s) { sequence = s; return this; }
		Spec fps(float f) { fps = f; return this; }
		Spec blended() { additive = false; return this; }
		Spec brightness(float b) { brightness = b; return this; }
		Spec rate(float r) { rate = r; return this; }
		Spec burst(int n) { burst = n; return this; }
		Spec duration(float s) { duration = s; return this; }
		Spec life(float min, float max) { lifeMin = min; lifeMax = max; return this; }
		Spec size(float start, float end) { sizeStart = start; sizeEnd = end; return this; }
		Spec sizeJitter(float j) { sizeJitter = j; return this; }
		Spec stretch(float s) { stretch = s; return this; }
		Spec color(float[] start, float[] end) { colorStart = start; colorEnd = end; return this; }
		Spec color(float[] start, float[] mid, float[] end) { colorStart = start; colorMid = mid; colorEnd = end; return this; }
		Spec alpha(float a, float in, float out) { alpha = a; fadeIn = in; fadeOut = out; return this; }
		Spec spawnRadius(float r) { spawnRadius = r; return this; }
		Spec velocity(double x, double y, double z, float random) { velocity = new Vec3(x, y, z); velocityRandom = random; return this; }
		Spec velocityRandom(float random) { velocityRandom = random; return this; }
		Spec gravity(float g) { gravity = g; return this; }
		Spec drag(float d) { drag = d; return this; }
		Spec inherit(float f) { inherit = f; return this; }
		Spec orient(Orient o) { orient = o; return this; }
		Spec roll(float r, float s) { roll = r; spin = s; return this; }
	}

	private final Spec spec;
	private final Supplier<Vec3> anchor;
	/** Forward, left, up for local velocities (null: world). */
	private final Supplier<Vec3[]> frame;
	private final Random rng;
	private final FxPack pack;
	private int count;
	private double[] pos = new double[3 * 16], vel = new double[3 * 16];
	private float[] age = new float[16], life = new float[16], size = new float[16], roll = new float[16], spin = new float[16];
	private int[] seq = new int[16], frameOffset = new int[16];
	private Vec3 last;
	private float time, carry;
	private boolean stopped, burstDone;

	public Emitter(Spec spec, FxPack pack, Supplier<Vec3> anchor, Supplier<Vec3[]> frame, long seed) {
		this.spec = spec;
		this.pack = pack;
		this.anchor = anchor;
		this.frame = frame;
		this.rng = new Random(seed);
	}

	@Override
	public void stop() {
		stopped = true;
	}

	public int count() {
		return count;
	}

	@Override
	public boolean tick(float dt) {
		Vec3 now = stopped ? null : anchor.get();
		if (now == null) stopped = true;
		boolean emitting = !stopped && (spec.duration < 0 || time < spec.duration);
		// Age and move what's out.
		for (int i = 0; i < count; i++) {
			age[i] += dt;
			if (age[i] >= life[i]) {
				remove(i--);
				continue;
			}
			int o = i * 3;
			vel[o + 1] -= spec.gravity * dt;
			double keep = Math.max(0, 1 - spec.drag * dt);
			for (int k = 0; k < 3; k++) {
				vel[o + k] *= keep;
				pos[o + k] += vel[o + k] * dt;
			}
			roll[i] += spin[i] * dt;
		}
		if (emitting) {
			Vec3 from = last == null ? now : last;
			Vec3 anchorVel = dt > 0 ? now.subtract(from).scale(1 / dt) : Vec3.ZERO;
			int n = 0;
			if (!burstDone) {
				n += spec.burst;
				burstDone = true;
			}
			carry += spec.rate * dt;
			int steady = (int) carry;
			carry -= steady;
			for (int i = 0; i < n + steady; i++) {
				// Steady parts spread along the anchor's path this frame, burst ones at its point now.
				float along = i < n ? 1 : (i - n + rng.nextFloat()) / Math.max(1, steady);
				spawn(from.lerp(now, along), anchorVel, dt * (1 - along));
			}
		}
		last = now;
		time += dt;
		return emitting || count > 0;
	}

	private void spawn(Vec3 at, Vec3 anchorVel, float alreadyAged) {
		if (count == life.length) grow();
		int i = count++, o = i * 3;
		Vec3 p = at;
		if (spec.spawnRadius > 0) p = p.add(unitBall().scale(spec.spawnRadius));
		Vec3 v = spec.velocity;
		Vec3[] f = frame == null ? null : frame.get();
		if (f != null) v = f[0].scale(v.x).add(f[1].scale(v.y)).add(f[2].scale(v.z));
		if (spec.velocityRandom > 0) v = v.add(unitBall().scale(spec.velocityRandom));
		v = v.add(anchorVel.scale(spec.inherit));
		pos[o] = p.x;
		pos[o + 1] = p.y;
		pos[o + 2] = p.z;
		vel[o] = v.x;
		vel[o + 1] = v.y;
		vel[o + 2] = v.z;
		age[i] = alreadyAged;
		life[i] = spec.lifeMin + rng.nextFloat() * (spec.lifeMax - spec.lifeMin);
		size[i] = 1 + (rng.nextFloat() * 2 - 1) * spec.sizeJitter;
		roll[i] = (rng.nextFloat() * 2 - 1) * spec.roll;
		spin[i] = (rng.nextFloat() * 2 - 1) * spec.spin;
		seq[i] = spec.sequence >= 0 ? spec.sequence : rng.nextInt(1 << 16);
		frameOffset[i] = rng.nextInt(1 << 16);
	}

	private Vec3 unitBall() {
		while (true) {
			double x = rng.nextDouble() * 2 - 1, y = rng.nextDouble() * 2 - 1, z = rng.nextDouble() * 2 - 1;
			if (x * x + y * y + z * z <= 1) return new Vec3(x, y, z);
		}
	}

	private void remove(int i) {
		int last = --count;
		if (i == last) return;
		System.arraycopy(pos, last * 3, pos, i * 3, 3);
		System.arraycopy(vel, last * 3, vel, i * 3, 3);
		age[i] = age[last];
		life[i] = life[last];
		size[i] = size[last];
		roll[i] = roll[last];
		spin[i] = spin[last];
		seq[i] = seq[last];
		frameOffset[i] = frameOffset[last];
	}

	private void grow() {
		int n = life.length * 2;
		pos = java.util.Arrays.copyOf(pos, n * 3);
		vel = java.util.Arrays.copyOf(vel, n * 3);
		age = java.util.Arrays.copyOf(age, n);
		life = java.util.Arrays.copyOf(life, n);
		size = java.util.Arrays.copyOf(size, n);
		roll = java.util.Arrays.copyOf(roll, n);
		spin = java.util.Arrays.copyOf(spin, n);
		seq = java.util.Arrays.copyOf(seq, n);
		frameOffset = java.util.Arrays.copyOf(frameOffset, n);
	}

	@Override
	public void draw(Vec3 eye, Vec3 right, Vec3 up, Out out) {
		if (count == 0) return;
		FxPack.Sheet sheet = pack == null ? null : pack.sheet(spec.texture);
		Vec3 view = up.cross(right);  // towards the camera
		float[] xyz = new float[12], uv = new float[8], rect = new float[4];
		int[] argb = new int[4];
		for (int i = 0; i < count; i++) {
			float t = age[i] / life[i];
			float s = (spec.sizeStart + (spec.sizeEnd - spec.sizeStart) * t) * size[i];
			float a = spec.alpha * fade(t);
			if (s <= 0 || a <= 0.002f) continue;
			int o = i * 3;
			double cx = pos[o] - eye.x, cy = pos[o + 1] - eye.y, cz = pos[o + 2] - eye.z;
			Vec3 ax, ay;  // half extents across and along
			if (spec.orient == Orient.FACING) {
				double c = Math.cos(roll[i]), sn = Math.sin(roll[i]);
				ax = right.scale(c * s).add(up.scale(sn * s));
				ay = up.scale(c * s).subtract(right.scale(sn * s));
			} else {
				Vec3 v = new Vec3(vel[o], vel[o + 1], vel[o + 2]);
				Vec3 dir = spec.orient == Orient.UP && v.lengthSqr() < 1e-4 ? new Vec3(0, 1, 0) : v;
				// The direction as seen on screen: drop its part towards the camera.
				Vec3 onScreen = dir.subtract(view.scale(dir.dot(view)));
				if (onScreen.lengthSqr() < 1e-8) onScreen = up;
				onScreen = onScreen.normalize();
				Vec3 side = onScreen.cross(view).normalize();
				float length = spec.orient == Orient.UP ? s * spec.stretch : Math.max(s, (float) v.length() * spec.stretch * 0.5f);
				ax = side.scale(s);
				ay = onScreen.scale(length);
			}
			corner(xyz, 0, cx, cy, cz, ax, ay, -1, -1);
			corner(xyz, 1, cx, cy, cz, ax, ay, 1, -1);
			corner(xyz, 2, cx, cy, cz, ax, ay, 1, 1);
			corner(xyz, 3, cx, cy, cz, ax, ay, -1, 1);
			frame(sheet, i, rect);
			uv[0] = rect[0]; uv[1] = rect[3];
			uv[2] = rect[2]; uv[3] = rect[3];
			uv[4] = rect[2]; uv[5] = rect[1];
			uv[6] = rect[0]; uv[7] = rect[1];
			float[] c = color(t);
			int col = Vfx.argb(c[0], c[1], c[2], a);
			argb[0] = argb[1] = argb[2] = argb[3] = col;
			out.quad(spec.texture, spec.additive, xyz, uv, argb, spec.brightness);
		}
	}

	private static void corner(float[] xyz, int k, double cx, double cy, double cz, Vec3 ax, Vec3 ay, int sx, int sy) {
		xyz[k * 3] = (float) (cx + ax.x * sx + ay.x * sy);
		xyz[k * 3 + 1] = (float) (cy + ax.y * sx + ay.y * sy);
		xyz[k * 3 + 2] = (float) (cz + ax.z * sx + ay.z * sy);
	}

	private float fade(float t) {
		float in = spec.fadeIn <= 0 ? 1 : Math.min(1, t / spec.fadeIn);
		float out = spec.fadeOut <= 0 ? 1 : Math.min(1, (1 - t) / spec.fadeOut);
		return Math.max(0, Math.min(in, out));
	}

	private float[] color(float t) {
		float[] a = spec.colorStart, b = spec.colorEnd;
		float u = t;
		if (spec.colorMid != null) {
			if (t < 0.5f) {
				b = spec.colorMid;
				u = t * 2;
			} else {
				a = spec.colorMid;
				u = (t - 0.5f) * 2;
			}
		}
		return new float[] {a[0] + (b[0] - a[0]) * u, a[1] + (b[1] - a[1]) * u, a[2] + (b[2] - a[2]) * u};
	}

	/** The part's sheet frame (u0 v0 u1 v1), or the whole texture. */
	private void frame(FxPack.Sheet sheet, int i, float[] rect) {
		rect[0] = 0;
		rect[1] = 0;
		rect[2] = 1;
		rect[3] = 1;
		if (sheet == null || sheet.sequences().isEmpty()) return;
		FxPack.Sequence s = sheet.sequences().get(Math.floorMod(seq[i], sheet.sequences().size()));
		if (s.frames().isEmpty()) return;
		int n = s.frames().size();
		int f = spec.fps > 0 ? Math.min(n - 1, (int) (age[i] * spec.fps)) : Math.floorMod(frameOffset[i], n);
		if (spec.fps > 0 && !s.clamp()) f = Math.floorMod((int) (age[i] * spec.fps) + frameOffset[i], n);
		float[] fr = s.frames().get(f);
		System.arraycopy(fr, 0, rect, 0, 4);
	}
}
