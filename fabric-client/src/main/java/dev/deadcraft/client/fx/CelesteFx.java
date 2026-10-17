package dev.deadcraft.client.fx;

import java.util.function.Supplier;
import net.minecraft.world.phys.Vec3;

/**
 * Celeste's effects, built by hand to match Deadlock's by eye (the user's screenshots: a compact saturated blue
 * flame at each wand end, white at the tip, with violet embers; a blue burst where a shot lands). Textures are
 * Deadlock's own, from the player's effects pack. Each flame has a blended blue body under its glowing
 * tongues, so it still reads against Minecraft's bright daylight, where additive light alone disappears.
 * Sizes in blocks (half-widths), speeds in blocks/s, colours linear.
 */
public final class CelesteFx {
	private CelesteFx() {
	}

	static final String GLOW = "materials/particle/glows/glow02.vtex";
	static final String LICKS = "materials/particle/fire/fire_stylized/fire_stylized_licks.vtex";
	static final String SPARKS = "materials/particle/sparks/sparks.vtex";
	static final String BEAM = "materials/particle/beam_hotwhite.vtex";
	static final String FLARE = "materials/particle/particle_flares/particle_flare_004b_mod.vtex";

	static final float[] WHITE_BLUE = {0.80f, 0.88f, 1.0f};
	static final float[] LIGHT_BLUE = {0.45f, 0.60f, 1.0f};
	static final float[] BLUE = {0.22f, 0.34f, 1.0f};
	static final float[] DEEP_BLUE = {0.10f, 0.16f, 0.75f};
	static final float[] VIOLET = {0.50f, 0.25f, 1.0f};
	static final float[] PINK_VIOLET = {0.85f, 0.40f, 1.0f};

	/** The flame always burning at one end of the wand. */
	static Vfx wandFlame(FxPack pack, Supplier<Vec3> end, long seed) {
		// The blue body: blended, so it shows over daylight.
		Emitter body = new Emitter(new Emitter.Spec().texture(LICKS).blended().rate(16).life(0.2f, 0.3f).size(0.04f, 0.018f).sizeJitter(0.25f)
			.stretch(1.5f).color(BLUE, DEEP_BLUE).alpha(0.85f, 0.15f, 0.6f).velocity(0, 0.42, 0, 0.1f).drag(1.5f).orient(Emitter.Orient.UP),
			pack, end, null, seed);
		// Glowing tongues over it: light blue at the base, blue and violet as they rise. Few and dim enough not to
		// pile up into white.
		Emitter tongues = new Emitter(new Emitter.Spec().texture(LICKS).rate(14).life(0.18f, 0.28f).size(0.036f, 0.014f).sizeJitter(0.3f)
			.stretch(1.6f).color(LIGHT_BLUE, BLUE, VIOLET).alpha(0.6f, 0.1f, 0.6f).brightness(1.2f).velocity(0, 0.5, 0, 0.12f).drag(1.5f)
			.orient(Emitter.Orient.UP), pack, end, null, seed + 1);
		Emitter embers = new Emitter(new Emitter.Spec().texture(GLOW).rate(5).life(0.6f, 1.1f).size(0.008f, 0.003f).color(PINK_VIOLET, VIOLET)
			.alpha(1, 0.1f, 0.5f).brightness(3).velocity(0, 0.35, 0, 0.3f).drag(0.8f).spawnRadius(0.02f), pack, end, null, seed + 2);
		Glow halo = Glow.steady(end, GLOW, 0.1f, BLUE, 0.25f, 1, 0.12f);
		// The white-hot point at the tip.
		Glow core = Glow.steady(end, GLOW, 0.016f, WHITE_BLUE, 1, 2.5f, 0.15f);
		return Vfx.group(halo, body, tongues, embers, core);
	}

	/** The flash at the wand's tip as a shot leaves, along {@code dir}. */
	static Vfx muzzleFlash(FxPack pack, Vec3 at, Vec3 dir, long seed) {
		Glow flash = Glow.flash(at, GLOW, 0.07f, WHITE_BLUE, 1, 3, 0.1f, 0.5f);
		Glow tint = Glow.flash(at, GLOW, 0.16f, BLUE, 0.45f, 1.5f, 0.15f, 0.7f);
		Emitter burst = new Emitter(new Emitter.Spec().texture(LICKS).burst(6).duration(0.01f).life(0.08f, 0.14f).size(0.035f, 0.015f).stretch(1.8f)
			.color(WHITE_BLUE, BLUE).alpha(1, 0, 0.7f).brightness(2.5f).velocity(dir.x * 2.5, dir.y * 2.5, dir.z * 2.5, 1.2f).drag(6)
			.orient(Emitter.Orient.UP), pack, () -> at, null, seed);
		Emitter sparks = new Emitter(new Emitter.Spec().texture(SPARKS).burst(8).duration(0.01f).life(0.1f, 0.2f).size(0.008f, 0.004f).stretch(0.06f)
			.color(WHITE_BLUE, VIOLET).alpha(1, 0, 0.6f).brightness(3).velocity(dir.x * 4, dir.y * 4, dir.z * 4, 2.5f).drag(4)
			.orient(Emitter.Orient.STREAK), pack, () -> at, null, seed + 1);
		return Vfx.group(tint, burst, sparks, flash);
	}

	/** The bullet in flight: a glowing head and a fading blue trail behind it. */
	static Vfx tracer(FxPack pack, Supplier<Vec3> bullet, long seed) {
		Ribbon trail = new Ribbon(bullet, BEAM, 0.045f, 0.07f, WHITE_BLUE, BLUE, 1, 2.5f);
		Glow head = Glow.steady(bullet, GLOW, 0.07f, WHITE_BLUE, 1, 3, 0.1f);
		Glow tint = Glow.steady(bullet, GLOW, 0.16f, BLUE, 0.4f, 1.5f, 0.1f);
		Emitter dust = new Emitter(new Emitter.Spec().texture(GLOW).rate(60).life(0.15f, 0.3f).size(0.012f, 0.004f).color(WHITE_BLUE, VIOLET)
			.alpha(0.8f, 0, 0.6f).brightness(2.5f).velocityRandom(0.3f), pack, bullet, null, seed);
		return Vfx.group(tint, trail, dust, head);
	}

	/** Where a shot lands: a blue burst facing out of the surface ({@code normal}), sparks and a ring. */
	static Vfx impact(FxPack pack, Vec3 at, Vec3 normal, long seed) {
		Vec3 p = at.add(normal.scale(0.03));
		Glow ball = Glow.flash(p, GLOW, 0.28f, BLUE, 0.8f, 1.8f, 0.3f, 1.3f);
		Glow core = Glow.flash(p, GLOW, 0.07f, WHITE_BLUE, 1, 3, 0.15f, 0.6f);
		Glow star = Glow.flash(p, FLARE, 0.18f, WHITE_BLUE, 1, 2.5f, 0.12f, 1.4f);
		Emitter sparks = new Emitter(new Emitter.Spec().texture(SPARKS).burst(14).duration(0.01f).life(0.15f, 0.3f).size(0.01f, 0.004f).stretch(0.07f)
			.color(WHITE_BLUE, BLUE).alpha(1, 0, 0.5f).brightness(3).velocity(normal.x * 3, normal.y * 3, normal.z * 3, 3.5f).gravity(6).drag(2)
			.orient(Emitter.Orient.STREAK), pack, () -> p, null, seed);
		// A thin shaft of light rising from the hit (Deadlock's impact streak).
		Emitter shaft = new Emitter(new Emitter.Spec().texture(LICKS).burst(3).duration(0.01f).life(0.25f, 0.35f).size(0.03f, 0.01f).stretch(6)
			.color(WHITE_BLUE, BLUE).alpha(0.8f, 0, 0.7f).brightness(2.5f).velocity(0, 1.6, 0, 0.1f).drag(3).orient(Emitter.Orient.UP),
			pack, () -> p, null, seed + 1);
		return Vfx.group(ball, shaft, sparks, star, core);
	}
}
