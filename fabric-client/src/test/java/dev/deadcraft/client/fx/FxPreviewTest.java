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

	interface Setup {
		void at(FxSystem s, float t);
	}

	static void strip(FxPack pack, String path, String name, float[] times, float unitsAcross, Setup setup, float stopAt) throws Exception {
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
			float[] c = s.cp(3).set ? s.cp(3).pos : s.cp(0).pos;
			frames[f] = new FxPreview(pack, SIDE, c.clone(), 256 / unitsAcross, 256, 256);
			s.camera(c[0] - 2000, c[1], c[2]);
			Renderers.draw(s, pack, SIDE, frames[f]);
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
