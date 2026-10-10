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
