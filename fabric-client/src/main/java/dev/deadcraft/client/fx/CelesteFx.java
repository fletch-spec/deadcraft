package dev.deadcraft.client.fx;

import java.util.function.Supplier;
import net.minecraft.world.phys.Vec3;

/**
 * Celeste's effects, built by hand to match Deadlock's by eye (the user's screenshots: a compact blue flame
 * at each wand end, white at the tip, with violet star sparkles and a thin wisp of smoke; a thin white wavy
 * tracer with pink sparkles behind a blue comet head). Textures are Deadlock's own, from the player's effects
 * pack. Sizes in blocks (half-widths), speeds in blocks/s, colours linear.
 *
 * <p>Abilities and movement are built from their names, animations and the control points of Deadlock's own
 * cast effects (where they start and point); their looks are guesses until checked against Deadlock.
 */
public final class CelesteFx {
	private CelesteFx() {
	}

	static final String GLOW = "materials/particle/glows/glow02.vtex";
	/** An animated wisp of ethereal flame (47 frames). */
	static final String FLAME = "materials/particle/flames/flame_ethereal.vtex";
	/** A four-pointed star: Celeste's sparkles. */
	static final String SPARKLE = "materials/particle/particle_flares/flare_magic.vtex";
	static final String SMOKE = "materials/particle/smoke1/smoke_soft.vtex";
	static final String SPARKS = "materials/particle/sparks/sparks.vtex";
	static final String BEAM = "materials/particle/beam_hotwhite.vtex";
	static final String FLARE = "materials/particle/particle_flares/particle_flare_004b_mod.vtex";
	/** A flat, wide lens streak (a muzzle flash). */
	static final String STREAK = "materials/particle/particle_flares/particle_flare_006_white.vtex";
	/** A soft ring of light round a clear middle. */
	static final String RING = "materials/particle/ring/particle_ring_shockwave.vtex";
	static final String SHARD = "materials/particle/shape/shape_shard.vtex";
	static final String SHIELD = "materials/particle/symbols/symbol_shield.vtex";

	static final float[] WHITE = {1, 1, 1};
	static final float[] WHITE_BLUE = {0.80f, 0.88f, 1.0f};
	static final float[] LIGHT_BLUE = {0.45f, 0.60f, 1.0f};
	static final float[] BLUE = {0.22f, 0.34f, 1.0f};
	static final float[] VIOLET = {0.50f, 0.25f, 1.0f};
	static final float[] PINK_VIOLET = {0.85f, 0.40f, 1.0f};
	static final float[] SMOKE_TEAL = {0.42f, 0.58f, 0.58f};

	// ---- the wand ---------------------------------------------------------------------------------

	/** The flame always burning at one end of the wand: it stays on the wand, a short smear when swung. */
	static Vfx wandFlame(FxPack pack, Supplier<Vec3> end, long seed) {
		Emitter flame = new Emitter(new Emitter.Spec().texture(FLAME).fps(120).local().rate(34).life(0.22f, 0.34f).size(0.04f, 0.02f).sizeJitter(0.25f)
			.stretch(1.3f).color(LIGHT_BLUE, BLUE, VIOLET).alpha(0.9f, 0.1f, 0.5f).brightness(1.5f).velocity(0, 0.22, 0, 0.06f).drag(1)
			.spawnRadius(0.006f).orient(Emitter.Orient.UP), pack, end, null, seed);
		// A blended copy under it so the flame still reads blue over daylight, where light alone disappears.
		Emitter body = new Emitter(new Emitter.Spec().texture(FLAME).fps(120).blended().local().rate(14).life(0.2f, 0.3f).size(0.036f, 0.018f)
			.stretch(1.2f).color(LIGHT_BLUE, BLUE).alpha(0.55f, 0.1f, 0.5f).velocity(0, 0.2, 0, 0.05f).orient(Emitter.Orient.UP), pack, end, null, seed + 1);
		// Only while the wand moves: a short smear behind the swing.
		Emitter smear = new Emitter(new Emitter.Spec().texture(FLAME).fps(120).perBlock(40).life(0.06f, 0.1f).size(0.024f, 0.01f).color(LIGHT_BLUE, BLUE)
			.alpha(0.6f, 0.1f, 0.6f).brightness(1.5f).roll(3.1f, 0), pack, end, null, seed + 2);
		Emitter sparkles = new Emitter(new Emitter.Spec().texture(SPARKLE).rate(4).life(0.5f, 0.9f).size(0.01f, 0.003f).color(PINK_VIOLET, VIOLET)
			.alpha(1, 0.1f, 0.5f).brightness(3).velocity(0, 0.3, 0, 0.25f).drag(0.8f).spawnRadius(0.015f).roll(0.4f, 2), pack, end, null, seed + 3);
		Emitter smoke = new Emitter(new Emitter.Spec().texture(SMOKE).blended().rate(6).life(0.5f, 0.8f).size(0.006f, 0.02f).stretch(3)
			.color(SMOKE_TEAL, SMOKE_TEAL).alpha(0.14f, 0.2f, 0.6f).velocity(0, 0.55, 0, 0.05f).drag(0.5f).orient(Emitter.Orient.UP), pack, end, null, seed + 4);
		Glow halo = Glow.steady(end, GLOW, 0.06f, BLUE, 0.14f, 1, 0.12f);
		// The white-hot point at the tip.
		Glow core = Glow.steady(end, GLOW, 0.014f, WHITE_BLUE, 1, 3, 0.15f);
		return Vfx.group(halo, smoke, body, smear, flame, sparkles, core);
	}

	// ---- the gun ----------------------------------------------------------------------------------

	/** The flash at the wand's tip as a shot leaves, along {@code dir}. */
	static Vfx muzzleFlash(FxPack pack, Vec3 at, Vec3 dir, long seed) {
		Glow flash = Glow.flash(at, GLOW, 0.05f, WHITE_BLUE, 1, 3, 0.08f, 0.5f);
		Glow streak = Glow.flash(at, STREAK, 0.12f, LIGHT_BLUE, 1, 2.5f, 0.07f, 0.6f);
		Glow tint = Glow.flash(at, GLOW, 0.12f, BLUE, 0.4f, 1.5f, 0.12f, 0.7f);
		Emitter flames = new Emitter(new Emitter.Spec().texture(FLAME).fps(160).burst(5).duration(0.01f).life(0.08f, 0.14f).size(0.03f, 0.01f)
			.color(WHITE_BLUE, BLUE).alpha(1, 0, 0.7f).brightness(2.5f).velocity(dir.x * 2, dir.y * 2, dir.z * 2, 0.8f).drag(6).roll(3.1f, 0), pack,
			() -> at, null, seed);
		Emitter sparkles = new Emitter(new Emitter.Spec().texture(SPARKLE).burst(5).duration(0.01f).life(0.2f, 0.35f).size(0.01f, 0.003f)
			.color(PINK_VIOLET, VIOLET).alpha(1, 0, 0.5f).brightness(3).velocity(dir.x * 2, dir.y * 2, dir.z * 2, 1.5f).drag(3).roll(0.4f, 3), pack,
			() -> at, null, seed + 1);
		return Vfx.group(tint, flames, sparkles, streak, flash);
	}

	/** The bullet in flight: a thin wavy white trail with pink sparkles, behind a blue comet head. */
	static Vfx tracer(FxPack pack, Supplier<Vec3> bullet, long seed) {
		Ribbon trail = new Ribbon(new Wobble(bullet, 0.025, 1.6), BEAM, 0.012f, 0.2f, WHITE_BLUE, LIGHT_BLUE, 0.9f, 2.2f);
		Ribbon comet = new Ribbon(bullet, GLOW, 0.03f, 0.025f, WHITE_BLUE, BLUE, 1, 3);
		Glow head = Glow.steady(bullet, GLOW, 0.03f, WHITE_BLUE, 1, 3, 0.1f);
		Glow tint = Glow.steady(bullet, GLOW, 0.09f, BLUE, 0.3f, 1.5f, 0.1f);
		Emitter sparkles = new Emitter(new Emitter.Spec().texture(SPARKLE).perBlock(2.5f).life(0.3f, 0.6f).size(0.011f, 0.003f).color(PINK_VIOLET, VIOLET)
			.alpha(1, 0.05f, 0.6f).brightness(2.5f).velocityRandom(0.2f).drag(2).roll(0.4f, 2), pack, bullet, null, seed);
		return Vfx.group(tint, trail, sparkles, comet, head);
	}

	/** Where a shot lands: a blue burst and ring on the surface ({@code normal} out of it), sparks and sparkles. */
	static Vfx impact(FxPack pack, Vec3 at, Vec3 normal, long seed) {
		Vec3 p = at.add(normal.scale(0.03));
		Glow ball = Glow.flash(p, GLOW, 0.2f, BLUE, 0.7f, 1.3f, 0.25f, 1.3f);
		Glow core = Glow.flash(p, GLOW, 0.05f, WHITE_BLUE, 1, 3, 0.12f, 0.6f);
		Glow star = Glow.flash(p, FLARE, 0.14f, WHITE_BLUE, 1, 2.5f, 0.1f, 1.4f);
		Ring ring = new Ring(at.add(normal.scale(0.01)), normal, RING, 0.05f, 0.35f, 0.3f, LIGHT_BLUE, 0.6f, 1.5f, true);
		Emitter sparks = new Emitter(new Emitter.Spec().texture(SPARKS).burst(10).duration(0.01f).life(0.15f, 0.3f).size(0.01f, 0.004f).stretch(0.07f)
			.color(WHITE_BLUE, BLUE).alpha(1, 0, 0.5f).brightness(3).velocity(normal.x * 3, normal.y * 3, normal.z * 3, 3.5f).gravity(6).drag(2)
			.orient(Emitter.Orient.STREAK), pack, () -> p, null, seed);
		Emitter sparkles = new Emitter(new Emitter.Spec().texture(SPARKLE).burst(6).duration(0.01f).life(0.3f, 0.6f).size(0.012f, 0.003f)
			.color(PINK_VIOLET, VIOLET).alpha(1, 0, 0.5f).brightness(3).velocity(normal.x * 1.2, normal.y * 1.2, normal.z * 1.2, 1.2f).drag(2.5f)
			.roll(0.4f, 3), pack, () -> p, null, seed + 1);
		return Vfx.group(ring, ball, sparks, sparkles, star, core);
	}

	// ---- abilities --------------------------------------------------------------------------------

	/** Radiant Blast's cast (1): a white-violet burst at the wand throwing a fan of light shards along {@code dir}. */
	static Vfx radiantBlast(FxPack pack, Vec3 at, Vec3 dir, long seed) {
		Glow flash = Glow.flash(at, GLOW, 0.12f, WHITE_BLUE, 1, 3, 0.15f, 1.6f);
		Glow tint = Glow.flash(at, GLOW, 0.3f, VIOLET, 0.35f, 1, 0.25f, 1.2f);
		Glow star = Glow.flash(at, FLARE, 0.25f, WHITE_BLUE, 1, 2, 0.15f, 1.5f);
		Emitter shards = new Emitter(new Emitter.Spec().texture(SHARD).burst(12).duration(0.01f).life(0.12f, 0.22f).size(0.02f, 0.01f).stretch(0.05f)
			.color(WHITE_BLUE, PINK_VIOLET).alpha(1, 0, 0.5f).brightness(3).velocity(dir.x * 18, dir.y * 18, dir.z * 18, 6).drag(4)
			.orient(Emitter.Orient.STREAK), pack, () -> at, null, seed);
		Emitter sparkles = new Emitter(new Emitter.Spec().texture(SPARKLE).burst(14).duration(0.01f).life(0.3f, 0.6f).size(0.014f, 0.004f)
			.color(PINK_VIOLET, VIOLET).alpha(1, 0, 0.5f).brightness(3).velocity(dir.x * 6, dir.y * 6, dir.z * 6, 4).drag(3).roll(0.4f, 3), pack,
			() -> at, null, seed + 1);
		return Vfx.group(tint, shards, sparkles, star, flash);
	}

	/** One of Radiant Blast's bullets in flight: a white-hot dagger of light trailing pink sparkles. */
	static Vfx radiantDagger(FxPack pack, Supplier<Vec3> bullet, long seed) {
		Ribbon trail = new Ribbon(bullet, BEAM, 0.03f, 0.06f, WHITE, PINK_VIOLET, 1, 3);
		Glow head = Glow.steady(bullet, GLOW, 0.05f, WHITE_BLUE, 1, 3, 0.1f);
		Emitter sparkles = new Emitter(new Emitter.Spec().texture(SPARKLE).perBlock(4).life(0.3f, 0.5f).size(0.012f, 0.003f).color(PINK_VIOLET, VIOLET)
			.alpha(1, 0.05f, 0.6f).brightness(2.5f).velocityRandom(0.3f).drag(2).roll(0.4f, 2), pack, bullet, null, seed);
		return Vfx.group(trail, sparkles, head);
	}

	/** Where a Radiant Blast bullet lands: a small pink-white pop. */
	static Vfx radiantHit(FxPack pack, Vec3 at, Vec3 normal, long seed) {
		Vec3 p = at.add(normal.scale(0.03));
		Glow tint = Glow.flash(p, GLOW, 0.12f, PINK_VIOLET, 0.6f, 1.8f, 0.2f, 1.3f);
		Glow core = Glow.flash(p, GLOW, 0.04f, WHITE, 1, 3, 0.1f, 0.6f);
		Emitter sparkles = new Emitter(new Emitter.Spec().texture(SPARKLE).burst(5).duration(0.01f).life(0.25f, 0.5f).size(0.012f, 0.003f)
			.color(PINK_VIOLET, VIOLET).alpha(1, 0, 0.5f).brightness(3).velocity(normal.x, normal.y, normal.z, 1).drag(2.5f).roll(0.4f, 3), pack,
			() -> p, null, seed);
		return Vfx.group(tint, sparkles, core);
	}

	/**
	 * Prismatic Guard (2): a starburst at the horn, a shield glyph over her and prismatic sparkles round her
	 * body for two seconds.
	 */
	static Vfx prismaticGuard(FxPack pack, Supplier<Vec3> horn, Supplier<Vec3> body, long seed) {
		Glow star = Glow.flash(horn, FLARE, 0.18f, WHITE_BLUE, 1, 2, 0.3f, 1.5f);
		Glow streak = Glow.flash(horn, STREAK, 0.22f, LIGHT_BLUE, 1, 2, 0.25f, 0.8f);
		Glow glyph = Glow.flash(body, SHIELD, 0.35f, LIGHT_BLUE, 0.5f, 1.5f, 0.6f, 1.2f);
		Emitter shimmer = new Emitter(new Emitter.Spec().texture(SPARKLE).rainbow().local().rate(40).duration(2).life(0.3f, 0.6f).size(0.04f, 0.01f)
			.alpha(1, 0.1f, 0.5f).brightness(2.5f).spawnRadius(0.5f).velocity(0, 0.3, 0, 0.3f).roll(0.4f, 3), pack, body, null, seed);
		return Vfx.group(glyph, shimmer, streak, star);
	}

	/**
	 * Luminous Strike (3): light gathers at the horn, then a pillar comes down from the sky onto {@code target}
	 * (where she aimed) with a ring and a burst on the ground.
	 */
	static Vfx luminousStrike(FxPack pack, Supplier<Vec3> horn, Vec3 target, long seed) {
		Glow gather = Glow.flash(horn, FLARE, 0.18f, WHITE_BLUE, 1, 3, 0.25f, 0.4f);
		Vec3 sky = target.add(0, 14, 0);
		Vfx strike = Vfx.after(0.15f, () -> {
			Beam pillar = new Beam(sky, target, BEAM, 0.45f, 0.5f, WHITE_BLUE, 1, 3);
			Beam haze = new Beam(sky, target, GLOW, 1.2f, 0.6f, BLUE, 0.35f, 1.5f);
			Ring ring = new Ring(target.add(0, 0.02, 0), new Vec3(0, 1, 0), RING, 0.2f, 2.2f, 0.55f, LIGHT_BLUE, 0.8f, 2, true);
			Glow flash = Glow.flash(target.add(0, 0.2, 0), GLOW, 0.6f, WHITE_BLUE, 1, 1.5f, 0.3f, 1.4f);
			Emitter sparkles = new Emitter(new Emitter.Spec().texture(SPARKLE).burst(20).duration(0.01f).life(0.4f, 0.8f).size(0.02f, 0.005f)
				.color(WHITE_BLUE, VIOLET).alpha(1, 0, 0.5f).brightness(3).velocity(0, 3, 0, 3).gravity(3).drag(1.5f).spawnRadius(0.4f).roll(0.4f, 3),
				pack, () -> target, null, seed);
			return Vfx.group(haze, ring, pillar, sparkles, flash);
		});
		return Vfx.group(gather, strike);
	}

	/** Dazzling Orb (4) charging at the wand while she holds it: a white orb growing in a blue glow, sparkles about it. */
	static Vfx orbCharge(FxPack pack, Supplier<Vec3> at, long seed) {
		Glow orb = Glow.charging(at, GLOW, 0.04f, 2.5f, 1, WHITE_BLUE, 1, 2.5f, 0.08f);
		Glow halo = Glow.charging(at, GLOW, 0.1f, 2.2f, 1, BLUE, 0.4f, 1.5f, 0.1f);
		Glow star = Glow.charging(at, FLARE, 0.08f, 2.5f, 1, WHITE_BLUE, 0.8f, 2.5f, 0.2f);
		Emitter sparkles = new Emitter(new Emitter.Spec().texture(SPARKLE).local().rate(18).life(0.3f, 0.6f).size(0.014f, 0.004f).color(WHITE_BLUE, PINK_VIOLET)
			.alpha(1, 0.1f, 0.5f).brightness(3).spawnRadius(0.18f).velocityRandom(0.2f).drag(2).roll(0.4f, 3), pack, at, null, seed);
		return Vfx.group(halo, sparkles, star, orb);
	}

	/** The thrown orb in flight: a bright orb with a soft trail and sparkles. */
	static Vfx orbFlight(FxPack pack, Supplier<Vec3> orb, long seed) {
		Glow core = Glow.steady(orb, GLOW, 0.1f, WHITE_BLUE, 1, 3, 0.08f);
		Glow halo = Glow.steady(orb, GLOW, 0.25f, BLUE, 0.4f, 1.5f, 0.1f);
		Ribbon trail = new Ribbon(orb, GLOW, 0.08f, 0.15f, LIGHT_BLUE, BLUE, 0.7f, 2);
		Emitter sparkles = new Emitter(new Emitter.Spec().texture(SPARKLE).perBlock(3).life(0.4f, 0.7f).size(0.016f, 0.004f).rainbow()
			.alpha(1, 0.05f, 0.6f).brightness(2.5f).velocityRandom(0.4f).drag(2).roll(0.4f, 2), pack, orb, null, seed);
		return Vfx.group(halo, trail, sparkles, core);
	}

	/** The orb bursting: a blinding flash, a shockwave ring, a star, sparkles and shards. */
	static Vfx orbBurst(FxPack pack, Vec3 at, Vec3 normal, long seed) {
		// Big glows stay near plain brightness: overbright over a large card clips to a flat white disc.
		Glow flash = Glow.flash(at, GLOW, 1.2f, WHITE_BLUE, 0.8f, 1.2f, 0.4f, 1.3f);
		Glow core = Glow.flash(at, GLOW, 0.25f, WHITE, 1, 3, 0.25f, 1.2f);
		Glow tint = Glow.flash(at, GLOW, 2.4f, BLUE, 0.3f, 1, 0.6f, 1.2f);
		Glow star = Glow.flash(at, FLARE, 1, WHITE_BLUE, 1, 2, 0.3f, 1.5f);
		Ring ring = new Ring(at.add(normal.scale(0.02)), normal, RING, 0.3f, 3, 0.5f, LIGHT_BLUE, 0.5f, 1.2f, true);
		Emitter sparkles = new Emitter(new Emitter.Spec().texture(SPARKLE).burst(30).duration(0.01f).life(0.5f, 1).size(0.025f, 0.006f).rainbow()
			.alpha(1, 0, 0.5f).brightness(3).velocityRandom(6).drag(2).roll(0.4f, 3), pack, () -> at, null, seed);
		Emitter shards = new Emitter(new Emitter.Spec().texture(SHARD).burst(16).duration(0.01f).life(0.15f, 0.3f).size(0.03f, 0.01f).stretch(0.05f)
			.color(WHITE_BLUE, LIGHT_BLUE).alpha(1, 0, 0.5f).brightness(3).velocityRandom(14).drag(4).orient(Emitter.Orient.STREAK), pack, () -> at,
			null, seed + 1);
		return Vfx.group(tint, ring, shards, sparkles, star, flash, core);
	}

	// ---- movement ---------------------------------------------------------------------------------

	/** A dash along {@code dir}: speed lines round her body and a puff of sparkles at her feet. */
	static Vfx dash(FxPack pack, Supplier<Vec3> body, Vec3 feet, Vec3 dir, long seed) {
		Emitter lines = new Emitter(new Emitter.Spec().texture(BEAM).rate(120).duration(0.25f).life(0.12f, 0.2f).size(0.01f, 0.004f).stretch(0.6f)
			.color(WHITE_BLUE, LIGHT_BLUE).alpha(0.6f, 0.1f, 0.6f).brightness(1.5f).velocity(dir.x * 1.5, dir.y * 1.5, dir.z * 1.5, 0.2f)
			.spawnRadius(0.45f).orient(Emitter.Orient.STREAK), pack, body, null, seed);
		Emitter sparkles = new Emitter(new Emitter.Spec().texture(SPARKLE).burst(8).duration(0.01f).life(0.3f, 0.5f).size(0.014f, 0.004f)
			.color(WHITE_BLUE, VIOLET).alpha(1, 0, 0.5f).brightness(2.5f).velocity(-dir.x, 0.4, -dir.z, 1).drag(2.5f).spawnRadius(0.2f).roll(0.4f, 3),
			pack, () -> feet, null, seed + 1);
		return Vfx.group(lines, sparkles);
	}

	/** A jump in the air: a ring of light under her feet and sparkles falling from it. */
	static Vfx airJump(FxPack pack, Vec3 feet, long seed) {
		Ring ring = new Ring(feet, new Vec3(0, 1, 0), RING, 0.1f, 0.6f, 0.3f, LIGHT_BLUE, 0.7f, 2, true);
		Emitter sparkles = new Emitter(new Emitter.Spec().texture(SPARKLE).burst(8).duration(0.01f).life(0.3f, 0.6f).size(0.014f, 0.004f)
			.color(WHITE_BLUE, VIOLET).alpha(1, 0, 0.5f).brightness(2.5f).velocity(0, -1, 0, 1).drag(2).spawnRadius(0.25f).roll(0.4f, 3), pack,
			() -> feet, null, seed);
		return Vfx.group(ring, sparkles);
	}

	/**
	 * A point that weaves about the path it follows (the tracer's wavy line): a sideways and an up-down wave by
	 * distance travelled, {@code amplitude} blocks, one wave every {@code wavelength} blocks.
	 */
	static final class Wobble implements Supplier<Vec3> {
		private final Supplier<Vec3> path;
		private final double amplitude, wavelength;
		private Vec3 last, side, lift;
		private double travelled;

		Wobble(Supplier<Vec3> path, double amplitude, double wavelength) {
			this.path = path;
			this.amplitude = amplitude;
			this.wavelength = wavelength;
		}

		@Override
		public Vec3 get() {
			Vec3 p = path.get();
			if (p == null) return null;
			if (last != null) {
				Vec3 step = p.subtract(last);
				double d = step.length();
				if (d > 1e-6) {
					travelled += d;
					if (side == null) {
						Vec3 dir = step.scale(1 / d);
						side = dir.cross(Math.abs(dir.y) < 0.95 ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0)).normalize();
						lift = side.cross(dir).normalize();
					}
				}
			}
			last = p;
			if (side == null) return p;
			// Grows in over the first block so the line leaves the wand cleanly.
			double k = travelled * 2 * Math.PI / wavelength, a = amplitude * Math.min(1, travelled);
			return p.add(side.scale(a * Math.sin(k))).add(lift.scale(a * 0.6 * Math.sin(k * 0.7 + 1.3)));
		}
	}
}
