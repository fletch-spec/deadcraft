package dev.deadcraft.client.fx;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Writes preview strips of Celeste's effects to build/fx-preview (from a real export; skipped without one),
 * to look at an effect's shape, size and colour outside the game. Each frame is 64 units (one block) across.
 */
class FxPreviewTest {
	private static final Path OUT = Path.of("build", "fx-preview");
	private static final Renderers.View SIDE = new Renderers.View(new float[] {-2000, 0, 0}, new float[] {0, -1, 0}, new float[] {0, 0, 1});
	/** From above, +x up the image. */
	private static final Renderers.View TOP = new Renderers.View(new float[] {0, 0, 3000}, new float[] {0, -1, 0}, new float[] {1, 0, 0});
	/** From the side: +x to the right. */
	private static final Renderers.View FRONT = new Renderers.View(new float[] {0, -2000, 0}, new float[] {1, 0, 0}, new float[] {0, 0, 1});

	interface Setup {
		void at(FxSystem s, float t);
	}

	static void strip(FxPack pack, String path, String name, float[] times, float unitsAcross, Setup setup, float stopAt) throws Exception {
		strip(pack, path, name, times, unitsAcross, setup, stopAt, SIDE);
	}

	static void strip(FxPack pack, String path, String name, float[] times, float unitsAcross, Setup setup, float stopAt, Renderers.View view) throws Exception {
		if (path == null) return;
		FxSystem s = new FxSystem(pack, path, 42);
		FxPreview[] frames = new FxPreview[times.length];
		float t = 0, dt = 1 / 120f;
		boolean stopped = false;
		for (int f = 0; f < times.length; f++) {
			while (t < times[f]) {
				setup.at(s, t);
				if (!stopped && t >= stopAt) {
					s.stop();
					stopped = true;
				}
				s.update(dt);
				t += dt;
			}
			float[] c = view == SIDE ? (s.cp(3).set ? s.cp(3).pos : s.cp(0).pos).clone() : new float[3];
			frames[f] = new FxPreview(pack, view, c, 256 / unitsAcross, 256, 256);
			s.camera(c[0] + view.eye()[0], c[1] + view.eye()[1], c[2] + view.eye()[2]);
			Renderers.draw(s, pack, new Renderers.View(new float[] {c[0] + view.eye()[0], c[1] + view.eye()[1], c[2] + view.eye()[2]}, view.right(), view.up()), frames[f]);
		}
		FxPreview.writeStrip(frames, OUT.resolve(name + ".png"));
		// What each system drew in the last frame.
		s.forEach(sys -> {
			float r = 0, al = 0, cr = 0, cg = 0, cb = 0;
			for (Particle p : sys.particles) {
				r += p.radius;
				al += p.alpha;
				cr += p.color[0];
				cg += p.color[1];
				cb += p.color[2];
			}
			int n = Math.max(1, sys.particles.size());
			StringBuilder rs = new StringBuilder();
			for (Renderers.Renderer re : sys.renderers) rs.append(re.getClass().getSimpleName()).append(re.additive ? "+" : "~").append(re.texture == null ? "-" : re.texture.substring(re.texture.lastIndexOf('/') + 1)).append(' ');
			System.out.printf("  %s: %d particles, radius %.1f, alpha %.2f, colour %.2f %.2f %.2f; %s%n", sys.def.path.substring(sys.def.path.lastIndexOf('/') + 1),
				sys.particles.size(), r / n, al / n, cr / n, cg / n, cb / n, rs);
		});
		StringBuilder q = new StringBuilder();
		for (FxPreview p : frames) q.append(p.quads).append(' ');
		System.out.println(name + ": quads per frame " + q.toString().trim());
	}

	/** Minecraft's clear daytime sky (sRGB), behind most third-person views. */
	static final float[] DAY_SKY = {0.47f, 0.65f, 1f};
	/** Pixels per unit in game: 180 units from the camera, about 59 deg vertical field of view, 1440 pixels high. */
	static final float GAME_PIXELS_PER_UNIT = 7;

	/** One run of an effect, drawn at each time over a background, with bloom at a strength (0: none). */
	static FxPreview[] frames(FxPack pack, String path, float[] times, Setup setup, float stopAt, float[] background, float bloom) {
		FxSystem s = new FxSystem(pack, path, 42);
		FxPreview[] frames = new FxPreview[times.length];
		float t = 0, dt = 1 / 120f;
		boolean stopped = false;
		for (int f = 0; f < times.length; f++) {
			while (t < times[f]) {
				setup.at(s, t);
				if (!stopped && t >= stopAt) {
					s.stop();
					stopped = true;
				}
				s.update(dt);
				t += dt;
			}
			float[] c = (s.cp(3).set ? s.cp(3).pos : s.cp(0).pos).clone();
			float[] eye = {c[0] + SIDE.eye()[0], c[1] + SIDE.eye()[1], c[2] + SIDE.eye()[2]};
			frames[f] = new FxPreview(pack, SIDE, c, GAME_PIXELS_PER_UNIT, 256, 256, background);
			s.camera(eye[0], eye[1], eye[2]);
			Renderers.draw(s, pack, new Renderers.View(eye, SIDE.right(), SIDE.up()), frames[f]);
			if (bloom > 0) frames[f].bloom(bloom);
		}
		return frames;
	}

	/**
	 * Bloom comparison at the game's scale (256 pixels is about 37 units, 0.6 blocks): rows are night, night with
	 * bloom, day sky, day sky with bloom.
	 */
	static void bloomRows(FxPack pack, String path, String name, float[] times, Setup setup, float stopAt) throws Exception {
		if (path == null) return;
		float b = FxSettings.DEFAULT_BLOOM;
		FxPreview.writeRows(new FxPreview[][] {frames(pack, path, times, setup, stopAt, null, 0), frames(pack, path, times, setup, stopAt, null, b),
			frames(pack, path, times, setup, stopAt, DAY_SKY, 0), frames(pack, path, times, setup, stopAt, DAY_SKY, b)}, OUT.resolve("bloom_" + name + ".png"));
	}

	/**
	 * Over Minecraft's daytime sky the wand flame's light shows only in its cards (round 5: "the space around the
	 * wand where there should be a flame is empty"); bloom spreads it into a halo you can see around the wand.
	 */
	@Test
	void bloomShowsTheWandFlameAgainstTheDaySky() throws Exception {
		try (FxPack pack = FxRealPackTest.celeste()) {
			String flame = pack.effect(FxRealPackTest.GUN, "m_BatonFlameParticle");
			Setup at = (s, t) -> {
				for (int cp : new int[] {0, 3}) {
					s.cp(cp).position(0, 0, 0);
					s.cp(cp).orient(0, 0, 1, 1, 0, 0);
				}
			};
			FxPreview p0 = frames(pack, flame, new float[] {1}, at, 99, DAY_SKY, 0)[0], p1 = frames(pack, flame, new float[] {1}, at, 99, DAY_SKY, FxSettings.DEFAULT_BLOOM)[0];
			float[] plain = ring(p0, 6, 10), glowing = ring(p1, 6, 10);
			System.out.printf("around the flame over the day sky: %.3f without bloom, %.3f with (sky %.3f)%n", plain[0], glowing[0], plain[1]);
			// 6 to 10 units out (beyond the flame's cards, 40 to 70 pixels on a 1440-pixel screen): sky without bloom...
			org.junit.jupiter.api.Assertions.assertTrue(plain[0] - plain[1] < 0.01f, "without bloom the space around the flame is sky");
			// ...and visibly brighter with it.
			org.junit.jupiter.api.Assertions.assertTrue(glowing[0] - plain[1] > 0.05f, "bloom brightens the space around the flame");
		}
	}

	/** Mean brightness in a ring (units from the frame's centre, the flame), and in the top-left corner (sky). */
	private static float[] ring(FxPreview f, float from, float to) {
		float ring = 0, corner = 0;
		int nr = 0, nc = 0;
		for (int y = 0; y < f.height; y++) {
			for (int x = 0; x < f.width; x++) {
				float d = (float) Math.hypot(x + 0.5f - f.width / 2f, y + 0.5f - f.height / 2f) / GAME_PIXELS_PER_UNIT;
				int o = (y * f.width + x) * 3;
				float v = (Math.min(1, f.rgb[o]) + Math.min(1, f.rgb[o + 1]) + Math.min(1, f.rgb[o + 2])) / 3;
				if (d >= from && d <= to) {
					ring += v;
					nr++;
				} else if (x < 8 && y < 8) {
					corner += v;
					nc++;
				}
			}
		}
		return new float[] {ring / nr, corner / nc};
	}

	@Test
	void bloomPreviews() throws Exception {
		try (FxPack pack = FxRealPackTest.celeste()) {
			String gun = FxRealPackTest.GUN;
			bloomRows(pack, pack.effect(gun, "m_BatonFlameParticle"), "wand_flame", new float[] {0.1f, 0.3f, 0.6f, 1f, 2f}, (s, t) -> {
				for (int cp : new int[] {0, 3}) {
					s.cp(cp).position(0, 0, 0);
					s.cp(cp).orient(0, 0, 1, 1, 0, 0);
				}
			}, 99);
			bloomRows(pack, pack.effect(gun, "m_mapWeaponInfos.primary.m_szMuzzleFlashEffectName"), "muzzle_flash",
				new float[] {0.02f, 0.05f, 0.1f, 0.2f, 0.4f}, (s, t) -> {
					s.cp(0).position(0, 0, 0);
					s.cp(0).orient(0, -1, 0, 0, 0, 1);
				}, 99);
		}
	}

	@Test
	void previews() throws Exception {
		try (FxPack pack = FxRealPackTest.celeste()) {
			String gun = FxRealPackTest.GUN;
			strip(pack, pack.effect(gun, "m_BatonFlameParticle"), "wand_flame", new float[] {0.1f, 0.3f, 0.6f, 1f, 2f}, 24, (s, t) -> {
				for (int cp : new int[] {0, 3}) {
					s.cp(cp).position(0, 0, 0);
					s.cp(cp).orient(0, 0, 1, 1, 0, 0);
				}
			}, 99);
			strip(pack, pack.effect(gun, "m_mapWeaponInfos.primary.m_szMuzzleFlashEffectName"), "muzzle_flash",
				new float[] {0.02f, 0.05f, 0.1f, 0.2f, 0.4f}, 64, (s, t) -> {
					s.cp(0).position(0, 0, 0);
					s.cp(0).orient(0, -1, 0, 0, 0, 1);
				}, 99);
			strip(pack, pack.effect(gun, "m_mapWeaponInfos.primary.m_strWeaponImpactEffect"), "impact",
				new float[] {0.02f, 0.05f, 0.1f, 0.25f, 0.5f}, 128, (s, t) -> {
					s.cp(0).position(0, 0, 0);
					s.cp(0).orient(0, -1, 0, 0, 0, 1);
				}, 99);
			// Radiant Blast from above (facing up the image): the caster at the bottom, the cone ahead.
			strip(pack, pack.effect("ability_unicorn_radiantblast", "m_CastParticle"), "radiant_blast", new float[] {0.05f, 0.15f, 0.3f, 0.6f, 1f},
				1600, (s, t) -> {
					float[][] at = {{0, 0, 0, 0}, {1, 779, 551, 0}, {2, 779, -551, 0}, {3, 0, 0, 120}, {4, 793, 0, 137}, {5, 30, 0, 76.5f},
						{10, 779, 0, 0}, {15, 779, 0, -100}};
					for (float[] p : at) {
						s.cp((int) p[0]).position(p[1] - 400, p[2], p[3]);
						s.cp((int) p[0]).orient(1, 0, 0, 0, 0, 1);
					}
				}, 99, TOP);
			strip(pack, pack.effect("ability_unicorn_prismaticguard", "m_CastParticle"), "prismatic_guard", new float[] {0.05f, 0.15f, 0.3f, 0.6f, 1f},
				400, (s, t) -> {
					float[][] at = {{0, 0, 0, 0}, {1, 500, 0, -100}, {10, 15.6f, 0, 0.5f}, {4, -5, 0, -5}, {6, 0, 0, -100}};
					for (float[] p : at) {
						s.cp((int) p[0]).position(p[1], p[2], p[3]);
						s.cp((int) p[0]).orient(1, 0, 0, 0, 0, 1);
					}
				}, 99, FRONT);
			strip(pack, pack.effect("ability_unicorn_dazzlingorb", "m_ChargeParticle"), "orb_charge", new float[] {0.1f, 0.3f, 0.6f, 1f, 1.5f},
				200, (s, t) -> {
					float[][] at = {{0, 0, 0, -60}, {1, 10, 0, 40}, {2, 30, 20, 0}, {3, -20, -20, -30}, {6, 0, 0, -60}};
					for (float[] p : at) {
						s.cp((int) p[0]).position(p[1], p[2], p[3]);
						s.cp((int) p[0]).orient(1, 0, 0, 0, 0, 1);
					}
				}, 99, FRONT);
			// The tracer flying sideways across the view at Celeste's bullet speed, then stopped at 0.3 s.
			strip(pack, pack.effect(gun, "m_mapWeaponInfos.primary.m_szBulletTravelTracerParticle"), "tracer",
				new float[] {0.05f, 0.15f, 0.3f, 0.35f, 0.5f}, 160, (s, t) -> {
					float y = Math.min(t, 0.3f) * -1968.5f;
					s.cp(0).position(0, y, 0);
					s.cp(0).orient(0, -1, 0, 0, 0, 1);
				}, 0.3f);
		}
	}
}
