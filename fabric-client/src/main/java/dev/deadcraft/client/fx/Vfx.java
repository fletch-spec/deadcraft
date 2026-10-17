package dev.deadcraft.client.fx;

import net.minecraft.world.phys.Vec3;

/**
 * A hand-built effect: stepped each frame and drawn as camera-facing cards in Minecraft's world (blocks).
 * Built from a few parts ({@link Emitter}, {@link Glow}, {@link Ribbon}) to match Deadlock's look by eye,
 * using the textures in the hero's effects pack.
 */
public interface Vfx {
	/** Steps by {@code dt} seconds; false once it has finished and can be dropped. */
	boolean tick(float dt);

	/** Draws its cards relative to the camera at {@code eye}, facing it ({@code right}, {@code up}: unit vectors). */
	void draw(Vec3 eye, Vec3 right, Vec3 up, Out out);

	/** Stops making new parts; what's already out plays to its end. */
	void stop();

	/**
	 * Where cards go: a texture (the pack's path, or {@link Renderers#SOFT_DOT}), additive or blended, 4 corners
	 * (x y z, blocks, relative to the camera), their texture coordinates, colours (linear ARGB, alpha in A) and
	 * brightness (1 plain, more is overbright: the shader rolls it off and bloom spreads it).
	 */
	interface Out {
		void quad(String texture, boolean additive, float[] xyz, float[] uv, int[] argb, float brightness);
	}

	/** Several effects as one (a flame's glow, tongues and embers). */
	static Vfx group(Vfx... parts) {
		return new Vfx() {
			@Override
			public boolean tick(float dt) {
				boolean alive = false;
				for (Vfx p : parts) alive |= p.tick(dt);
				return alive;
			}

			@Override
			public void draw(Vec3 eye, Vec3 right, Vec3 up, Out out) {
				for (Vfx p : parts) p.draw(eye, right, up, out);
			}

			@Override
			public void stop() {
				for (Vfx p : parts) p.stop();
			}
		};
	}

	/** Linear colour (0..1 each) and alpha to the ARGB the cards take. */
	static int argb(float r, float g, float b, float a) {
		return (clamp255(a) << 24) | (clamp255(r) << 16) | (clamp255(g) << 8) | clamp255(b);
	}

	private static int clamp255(float v) {
		return Math.max(0, Math.min(255, Math.round(v * 255)));
	}
}
