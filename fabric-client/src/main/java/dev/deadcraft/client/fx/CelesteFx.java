package dev.deadcraft.client.fx;

import java.util.function.Supplier;
import net.minecraft.world.phys.Vec3;

/**
 * Celeste's effects, built by hand to match Deadlock's by eye (the user's screenshots: a compact blue flame
 * at each wand end, white at the tip, with violet star sparkles and a thin wisp of smoke; a thin white wavy
 * tracer with pink sparkles behind a blue comet head). Textures are Deadlock's own, from the player's effects
 * pack. Sizes in blocks (half-widths), speeds in blocks/s, colours linear.
 *
 * <p>Abilities and movement are matched to the user's recordings of Deadlock (2026-10-20); where the plugin
 * doesn't say (the orb's flight) the motion is a guess.
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
		// Pink-violet round the flash (the recording).
		Glow tint = Glow.flash(at, GLOW, 0.12f, PINK_VIOLET, 0.4f, 1.3f, 0.12f, 0.7f);
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

	// ---- abilities (matched to the user's Deadlock recordings, 2026-10-20) --------------------------

	static final float[] CYAN = {0.35f, 0.85f, 1.0f};
	static final float[] DUSK = {0.12f, 0.1f, 0.18f};
	/** A sprite sheet of lens flares: 0 a star glow, 1 rainbow arcs, 3 a soft ring. */
	static final String LENS = "materials/particle/lens_flare/lens_flare.vtex";
	/** A soft-edged solid disc (a darkened circle on the ground). */
	static final String DISC = "materials/particle/glows/glow_large_tight.vtex";
	static final Vec3 UP = new Vec3(0, 1, 0);

	/** A colour round the rainbow, 0..1. */
	static float[] hue(float h) {
		float h6 = (h - (float) Math.floor(h)) * 6;
		return new float[] {Math.max(0, Math.min(1, Math.abs(h6 - 3) - 1)), Math.max(0, Math.min(1, 2 - Math.abs(h6 - 2))),
			Math.max(0, Math.min(1, 2 - Math.abs(h6 - 4)))};
	}

	/** A spark thrown from {@code at}: a glowing head on a thin trail, falling under {@code gravity} (a firework arc). */
	static Vfx arcSpark(Vec3 at, Vec3 velocity, float gravity, float life, float[] head, float[] trail, float width) {
		float[] clock = {0};
		Supplier<Vec3> p = () -> clock[0] > life ? null
			: at.add(velocity.scale(clock[0])).add(0, -0.5 * gravity * clock[0] * clock[0], 0);
		Ribbon line = new Ribbon(p, BEAM, width, 0.25f, WHITE_BLUE, trail, 0.9f, 2);
		Glow glow = Glow.steady(p, GLOW, width * 3.5f, head, 1, 3, 0.1f);
		return Vfx.clocked(clock, Vfx.group(line, glow));
	}

	/**
	 * Radiant Blast's cast (1), at the wand along {@code dir}: a fountain of glowing sparks arcing up and out, a
	 * burst of purple sparkles and big pink stars, cyan streaks, a wide arc of light sweeping out ahead, and the
	 * ground lit purple under her ({@code feet}).
	 */
	static Vfx radiantBlast(FxPack pack, Vec3 at, Vec3 dir, Vec3 feet, long seed) {
		java.util.Random r = new java.util.Random(seed);
		Vec3 flat = new Vec3(dir.x, 0, dir.z);
		flat = flat.lengthSqr() < 1e-6 ? new Vec3(1, 0, 0) : flat.normalize();
		Vec3 side = flat.cross(UP);
		java.util.List<Vfx> parts = new java.util.ArrayList<>();
		parts.add(new Ring(feet.add(0, 0.03, 0), UP, GLOW, 3, 3.5f, 0.8f, VIOLET, 0.45f, 1, true));
		parts.add(new Band(feet, 2.2f, 4.5f, (float) (at.y - feet.y) - 0.2f, (float) (at.y - feet.y) - 0.1f, 0.4f, 0.5f, 0.7f,
			Math.atan2(flat.z, flat.x), Math.toRadians(35), BEAM, new float[] {0.85f, 0.8f, 1}, 0.5f, 1, true));
		parts.add(Glow.flash(at, GLOW, 0.35f, VIOLET, 0.5f, 1, 0.4f, 1.2f));
		for (int i = 0; i < 8; i++) {
			Vec3 v = flat.scale(1.5 + r.nextDouble() * 2.5).add(side.scale((r.nextDouble() * 2 - 1) * 2.2)).add(0, 4 + r.nextDouble() * 2.5, 0);
			parts.add(arcSpark(at, v, 9, 0.55f + r.nextFloat() * 0.25f, CYAN, LIGHT_BLUE, 0.012f));
		}
		parts.add(new Emitter(new Emitter.Spec().texture(SPARKLE).burst(30).duration(0.01f).life(0.4f, 0.8f).size(0.014f, 0.004f)
			.color(PINK_VIOLET, VIOLET).alpha(1, 0, 0.5f).brightness(3).velocity(dir.x * 3, dir.y * 3, dir.z * 3, 2.5f).drag(2).roll(0.4f, 3), pack,
			() -> at, null, seed + 1));
		parts.add(new Emitter(new Emitter.Spec().texture(SPARKLE).burst(5).duration(0.01f).life(0.5f, 0.8f).size(0.06f, 0.02f).color(PINK_VIOLET, VIOLET)
			.alpha(1, 0.05f, 0.5f).brightness(2.5f).velocityRandom(1).drag(2).spawnRadius(0.4f), pack, () -> at, null, seed + 2));
		parts.add(new Emitter(new Emitter.Spec().texture(SPARKS).burst(14).duration(0.01f).life(0.15f, 0.3f).size(0.01f, 0.004f).stretch(0.07f)
			.color(CYAN, LIGHT_BLUE).alpha(1, 0, 0.5f).brightness(3).velocity(dir.x * 8, dir.y * 8, dir.z * 8, 5).drag(2).orient(Emitter.Orient.STREAK),
			pack, () -> at, null, seed + 3));
		parts.add(Glow.flash(at, GLOW, 0.1f, WHITE, 1, 3, 0.12f, 1.4f));
		return Vfx.group(parts.toArray(new Vfx[0]));
	}

	/** One of Radiant Blast's bullets in flight: a thin cyan streak. */
	static Vfx radiantDagger(FxPack pack, Supplier<Vec3> bullet, long seed) {
		Ribbon trail = new Ribbon(bullet, BEAM, 0.012f, 0.04f, CYAN, VIOLET, 0.9f, 2.5f);
		Glow head = Glow.steady(bullet, GLOW, 0.025f, CYAN, 1, 3, 0.1f);
		return Vfx.group(trail, head);
	}

	/** Where a Radiant Blast bullet lands: a small pink-white pop. */
	static Vfx radiantHit(FxPack pack, Vec3 at, Vec3 normal, long seed) {
		Vec3 p = at.add(normal.scale(0.03));
		Glow tint = Glow.flash(p, GLOW, 0.12f, PINK_VIOLET, 0.6f, 1.3f, 0.2f, 1.3f);
		Glow core = Glow.flash(p, GLOW, 0.04f, WHITE, 1, 3, 0.1f, 0.6f);
		Emitter sparkles = new Emitter(new Emitter.Spec().texture(SPARKLE).burst(5).duration(0.01f).life(0.25f, 0.5f).size(0.012f, 0.003f)
			.color(PINK_VIOLET, VIOLET).alpha(1, 0, 0.5f).brightness(3).velocity(normal.x, normal.y, normal.z, 1).drag(2.5f).roll(0.4f, 3), pack,
			() -> p, null, seed);
		return Vfx.group(tint, sparkles, core);
	}

	/** How long Prismatic Guard's bubble lasts, seconds (the recording: about 3.5). */
	static final float GUARD_S = 3.5f;

	/**
	 * Prismatic Guard (2): a crystal bubble round her for {@link #GUARD_S}: a soft blue rim and fill, crystal facets
	 * in rainbow tints, two ribbons circling her; at the start a starburst and rainbow arcs at the horn and a burst
	 * of white sparkles.
	 */
	static Vfx prismaticGuard(FxPack pack, Supplier<Vec3> horn, Supplier<Vec3> body, long seed) {
		float[] clock = {0};
		Supplier<Vec3> bubble = () -> clock[0] > GUARD_S ? null : body.get();
		Glow rim = Glow.steady(bubble, LENS, 1.15f, LIGHT_BLUE, 0.25f, 1, 0.02f).frame(pack, 3);
		Glow fill = Glow.steady(bubble, GLOW, 1.2f, BLUE, 0.12f, 1, 0.03f);
		Emitter facets = new Emitter(new Emitter.Spec().texture(SHARD).rainbow().local().rate(14).duration(GUARD_S - 0.5f).life(0.6f, 1).size(0.12f, 0.06f)
			.alpha(0.35f, 0.2f, 0.5f).brightness(1).spawnRadius(0.95f).roll(3.1f, 0.5f), pack, body, null, seed);
		Vfx[] orbits = new Vfx[2];
		for (int k = 0; k < 2; k++) {
			double tilt = k == 0 ? 0.35 : -0.5, speed = k == 0 ? 4.2 : -3.4, phase = k * 2.1;
			Supplier<Vec3> o = () -> {
				Vec3 c = bubble.get();
				if (c == null) return null;
				double a = clock[0] * speed + phase;
				return c.add(Math.cos(a) * 0.95, Math.sin(a) * 0.95 * tilt - 0.2, Math.sin(a) * 0.95);
			};
			orbits[k] = new Ribbon(o, BEAM, 0.05f, 0.3f, WHITE, LIGHT_BLUE, 0.7f, 1.5f);
		}
		Glow star = Glow.flash(horn, FLARE, 0.35f, WHITE_BLUE, 1, 2, 0.6f, 1.4f);
		Glow rainbow = Glow.flash(() -> {
			Vec3 h = horn.get();
			return h == null ? null : h.add(0, 0.5, 0);
		}, LENS, 1.6f, WHITE, 0.7f, 1.2f, 1.2f, 1.1f).frame(pack, 1);
		Emitter burst = new Emitter(new Emitter.Spec().texture(SPARKLE).burst(24).duration(0.01f).life(0.4f, 0.8f).size(0.05f, 0.02f).color(WHITE, PINK_VIOLET)
			.alpha(1, 0, 0.5f).brightness(3).spawnRadius(1).velocityRandom(0.8f).drag(2), pack, body, null, seed + 1);
		return Vfx.clocked(clock, Vfx.group(fill, facets, orbits[0], orbits[1], rim, rainbow, burst, star));
	}

	/** Luminous Strike's area round her (blocks) and the wait before it strikes (seconds): from the recording. */
	static final float STRIKE_RADIUS = 4.5f, STRIKE_DELAY_S = 1.5f;

	/**
	 * Luminous Strike (3), round {@code centre} (her feet when cast): a beam into the sky from the horn with a ring
	 * and rainbow arcs about her head; a darkened circle whose purple rim drops onto the ground, a spiral coming
	 * down, stars rising inside it; then the strike: pillars of light from the sky all over it, a rainbow curtain,
	 * purple flames round the edge, the circle lit purple and sparkles thrown up. {@code right}: the camera's right,
	 * which the curtain lies along.
	 */
	static Vfx luminousStrike(FxPack pack, Supplier<Vec3> horn, Vec3 centre, Vec3 right, long seed) {
		float R = STRIKE_RADIUS, total = STRIKE_DELAY_S + 0.7f;
		java.util.Random r = new java.util.Random(seed);
		float[] clock = {0};
		java.util.List<Vfx> parts = new java.util.ArrayList<>();
		Vec3 h0 = horn.get() != null ? horn.get() : centre.add(0, 1.5, 0);
		parts.add(new Beam(h0, h0.add(0, 20, 0), BEAM, 0.12f, 0.5f, new float[] {1, 0.85f, 1}, 1, 3));
		parts.add(Glow.flash(horn, LENS, 0.35f, WHITE, 1, 2, 0.5f, 1.2f).frame(pack, 3));
		parts.add(Glow.flash(horn, FLARE, 0.3f, PINK_VIOLET, 1, 2, 0.4f, 1.3f));
		parts.add(Glow.flash(horn, LENS, 1, WHITE, 0.6f, 1.2f, 0.5f, 1.2f).frame(pack, 1));
		parts.add(new Ring(centre.add(0, 0.02, 0), UP, DISC, R, R, total, DUSK, 0.35f, 1, false).hold(0.85f));
		parts.add(new Band(centre, R, R, 1.6f, 0, 0.25f, 0.4f, STRIKE_DELAY_S, 0, Math.PI, BEAM, PINK_VIOLET, 0.8f, 1.5f, true));
		parts.add(new Ribbon(() -> {
			float t = clock[0];
			if (t > 0.6f) return null;
			double f = t / 0.6, a = t * 14, rr = 0.7 * (1 - f) + 0.2;
			return centre.add(Math.cos(a) * rr, 12 * (1 - f) + 0.2, Math.sin(a) * rr);
		}, BEAM, 0.04f, 0.3f, PINK_VIOLET, VIOLET, 0.9f, 2));
		parts.add(new Emitter(new Emitter.Spec().texture(SPARKLE).disc(R * 0.95f).rate(20).duration(STRIKE_DELAY_S).life(0.5f, 0.9f).size(0.04f, 0.01f)
			.color(VIOLET, LIGHT_BLUE).alpha(1, 0.2f, 0.5f).brightness(2.5f).velocity(0, 0.6, 0, 0.1f), pack, () -> centre, null, seed));
		parts.add(new Emitter(new Emitter.Spec().texture(BEAM).disc(R * 0.95f).rate(15).duration(STRIKE_DELAY_S).life(0.4f, 0.7f).size(0.01f, 0.006f)
			.stretch(8).color(LIGHT_BLUE, VIOLET).alpha(0.6f, 0.2f, 0.5f).brightness(1.5f).velocity(0, 0.4, 0, 0).orient(Emitter.Orient.UP), pack,
			() -> centre, null, seed + 1));
		parts.add(Vfx.after(STRIKE_DELAY_S, () -> {
			java.util.List<Vfx> s = new java.util.ArrayList<>();
			for (int i = 0; i < 14; i++) {
				double a = r.nextDouble() * Math.PI * 2, d = R * Math.sqrt(r.nextDouble());
				Vec3 p = centre.add(Math.cos(a) * d, 0, Math.sin(a) * d);
				float[] c = i % 3 == 0 ? WHITE_BLUE : i % 3 == 1 ? LIGHT_BLUE : VIOLET;
				s.add(new Beam(p.add(0, 14, 0), p, BEAM, 0.06f + r.nextFloat() * 0.08f, 0.5f + r.nextFloat() * 0.2f, c, 1, 2.5f));
			}
			for (int i = 0; i < 7; i++) {
				Vec3 p = centre.add(right.scale((i - 3) * 0.9));
				float[] c = hue(0.62f + i / 7f);
				// Pastel: half towards white.
				s.add(new Beam(p, p.add(0, 9, 0), GLOW, 0.7f, 0.7f, new float[] {0.5f + c[0] * 0.5f, 0.5f + c[1] * 0.5f, 0.5f + c[2] * 0.5f}, 0.3f, 1));
			}
			s.add(new Emitter(new Emitter.Spec().texture(FLAME).fps(90).ring(R).burst(50).duration(0.01f).life(0.3f, 0.5f).size(0.5f, 0.3f).stretch(1.5f)
				.color(VIOLET, PINK_VIOLET).alpha(0.7f, 0.05f, 0.6f).brightness(1).velocity(0, 1.5, 0, 0.3f).orient(Emitter.Orient.UP), pack,
				() -> centre, null, seed + 2));
			s.add(new Ring(centre.add(0, 0.04, 0), UP, GLOW, R * 1.1f, R * 1.1f, 0.8f, VIOLET, 0.55f, 1.2f, true));
			s.add(new Band(centre, R, R, 0, 0, 0.3f, 0, 0.6f, 0, Math.PI, BEAM, VIOLET, 1, 2, true));
			s.add(new Emitter(new Emitter.Spec().texture(SPARKLE).disc(R).burst(40).duration(0.01f).life(0.6f, 1.2f).size(0.03f, 0.008f).color(WHITE, PINK_VIOLET)
				.alpha(1, 0, 0.5f).brightness(3).velocity(0, 1.5, 0, 0.8f).drag(1).roll(0.4f, 3), pack, () -> centre, null, seed + 3));
			return Vfx.group(s.toArray(new Vfx[0]));
		}));
		return Vfx.clocked(clock, Vfx.group(parts.toArray(new Vfx[0])));
	}

	/**
	 * Dazzling Orb (4) charging while she holds it up: a bright orb on the wand, a blue beam to it from the horn,
	 * a starburst at the horn.
	 */
	static Vfx orbCharge(FxPack pack, Supplier<Vec3> at, Supplier<Vec3> horn, long seed) {
		Glow orb = Glow.charging(at, GLOW, 0.09f, 1.3f, 0.6f, WHITE_BLUE, 1, 2.5f, 0.05f);
		Glow halo = Glow.charging(at, GLOW, 0.22f, 1.2f, 0.6f, BLUE, 0.4f, 1.2f, 0.08f);
		Glow star = Glow.steady(horn, FLARE, 0.12f, WHITE_BLUE, 0.9f, 2, 0.2f);
		Beam beam = new Beam(horn, at, BEAM, 0.03f, -1, LIGHT_BLUE, 0.9f, 2);
		Emitter sparkles = new Emitter(new Emitter.Spec().texture(SPARKLE).local().rate(10).life(0.3f, 0.6f).size(0.014f, 0.004f).color(WHITE_BLUE, PINK_VIOLET)
			.alpha(1, 0.1f, 0.5f).brightness(3).spawnRadius(0.15f).velocityRandom(0.2f).drag(2).roll(0.4f, 3), pack, at, null, seed);
		return Vfx.group(halo, beam, sparkles, star, orb);
	}

	/** The thrown orb in flight: a bright orb in a rainbow-ringed glow, a blue trail and star sparkles. */
	static Vfx orbFlight(FxPack pack, Supplier<Vec3> orb, long seed) {
		Glow core = Glow.steady(orb, GLOW, 0.09f, WHITE_BLUE, 1, 2.5f, 0.05f);
		Glow halo = Glow.steady(orb, GLOW, 0.3f, LIGHT_BLUE, 0.45f, 1.2f, 0.08f);
		Glow rainbow = Glow.steady(orb, LENS, 0.45f, WHITE, 0.5f, 1, 0.05f).frame(pack, 3);
		Ribbon trail = new Ribbon(orb, GLOW, 0.08f, 0.15f, LIGHT_BLUE, VIOLET, 0.7f, 1.5f);
		Emitter sparkles = new Emitter(new Emitter.Spec().texture(SPARKLE).perBlock(1.5f).life(0.4f, 0.7f).size(0.03f, 0.008f).color(WHITE_BLUE, CYAN)
			.alpha(1, 0.05f, 0.6f).brightness(2.5f).velocityRandom(0.4f).drag(2), pack, orb, null, seed);
		return Vfx.group(halo, trail, rainbow, sparkles, core);
	}

	/** Where the orb lands: a pillar of light, the ground lit round it, a flash with rainbow arcs and stars. */
	static Vfx orbBurst(FxPack pack, Vec3 at, Vec3 normal, long seed) {
		Beam pillar = new Beam(at, at.add(0, 3, 0), BEAM, 0.2f, 1, WHITE_BLUE, 1, 2.5f);
		Beam haze = new Beam(at, at.add(0, 3, 0), GLOW, 0.5f, 1, BLUE, 0.4f, 1);
		Ring ground = new Ring(at.add(0, 0.03, 0), UP, GLOW, 1.5f, 1.6f, 1, LIGHT_BLUE, 0.5f, 1, true).hold(0.5f);
		Glow flash = Glow.flash(at.add(normal.scale(0.2)), GLOW, 0.5f, WHITE_BLUE, 0.9f, 1.5f, 0.3f, 1.3f);
		Glow rainbow = Glow.flash(at.add(0, 0.5, 0), LENS, 0.8f, WHITE, 0.7f, 1.2f, 0.5f, 1.2f).frame(pack, 1);
		Emitter stars = new Emitter(new Emitter.Spec().texture(SPARKLE).burst(6).duration(0.01f).life(0.5f, 0.9f).size(0.05f, 0.02f).color(WHITE_BLUE, CYAN)
			.alpha(1, 0.05f, 0.5f).brightness(2.5f).velocity(0, 1, 0, 1).drag(2).spawnRadius(0.3f), pack, () -> at, null, seed);
		return Vfx.group(haze, ground, pillar, rainbow, stars, flash);
	}

	// ---- movement ---------------------------------------------------------------------------------

	/**
	 * A dash along {@code dir}. In the air (the recording): a purple ring round her across the dash, white speed
	 * lines left behind and purple stars. On the ground only faint lines.
	 */
	static Vfx dash(FxPack pack, Supplier<Vec3> body, Vec3 dir, boolean air, long seed) {
		Vec3 at = body.get();
		Emitter lines = new Emitter(new Emitter.Spec().texture(BEAM).burst(air ? 12 : 6).duration(0.01f).life(0.25f, 0.35f).size(0.012f, 0.006f).stretch(6)
			.color(WHITE, WHITE_BLUE).alpha(air ? 0.6f : 0.35f, 0, 0.6f).brightness(1.5f).velocity(dir.x * 0.5, dir.y * 0.5, dir.z * 0.5, 0)
			.spawnRadius(0.5f).orient(Emitter.Orient.STREAK), pack, () -> at, null, seed);
		if (!air) return lines;
		Ring ring = new Ring(at, dir, RING, 0.6f, 1.05f, 0.35f, PINK_VIOLET, 0.8f, 1.5f, true);
		Emitter stars = new Emitter(new Emitter.Spec().texture(SPARKLE).burst(5).duration(0.01f).life(0.4f, 0.7f).size(0.05f, 0.015f).color(PINK_VIOLET, VIOLET)
			.alpha(1, 0.05f, 0.5f).brightness(2.5f).spawnRadius(0.6f).velocityRandom(0.3f).drag(2), pack, () -> at, null, seed + 1);
		return Vfx.group(ring, lines, stars);
	}

	/** A jump (the recording): a cyan puff and swirl ring at her feet and light streaks shooting up. */
	static Vfx jump(FxPack pack, Vec3 feet, long seed) {
		Emitter puff = new Emitter(new Emitter.Spec().texture(SMOKE).burst(10).duration(0.01f).life(0.3f, 0.5f).size(0.2f, 0.4f).color(CYAN, BLUE)
			.alpha(0.4f, 0.05f, 0.7f).brightness(1).velocityRandom(0.8f).drag(3).spawnRadius(0.3f).roll(3.1f, 1), pack, () -> feet, null, seed);
		Ring swirl = new Ring(feet.add(0, 0.05, 0), UP, RING, 0.2f, 0.9f, 0.35f, CYAN, 0.7f, 1.5f, true);
		Emitter streaks = new Emitter(new Emitter.Spec().texture(BEAM).burst(6).duration(0.01f).life(0.15f, 0.25f).size(0.015f, 0.008f).stretch(10)
			.color(WHITE, CYAN).alpha(0.5f, 0, 0.6f).brightness(1.5f).velocity(0, 4, 0, 0.3f).spawnRadius(0.4f).orient(Emitter.Orient.UP), pack,
			() -> feet, null, seed + 1);
		return Vfx.group(puff, swirl, streaks);
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
