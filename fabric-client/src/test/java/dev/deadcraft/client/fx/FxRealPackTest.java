package dev.deadcraft.client.fx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Celeste's effects played headless from a real export (skipped without one: run
 * {@code tools/hero-export unicorn --fx}).
 */
class FxRealPackTest {
	static final String GUN = "citadel_weapon_unicorn_set";

	static FxPack celeste() throws Exception {
		String local = System.getenv("LOCALAPPDATA");
		Path file = Path.of(local == null ? System.getProperty("user.home") : local, "Deadcraft", "heroes", "unicorn.dcfx");
		assumeTrue(Files.exists(file), "no exported Celeste effects on this machine");
		return FxPack.load(file);
	}

	/** Counts quads, the textures they use, and how far from the origin they reach. */
	static final class Count implements Renderers.Sink {
		int quads, additive;
		float reach;
		final Set<String> textures = new HashSet<>();

		@Override
		public void quad(String texture, boolean add, float[] xyz, float[] uv, int[] argb) {
			quads++;
			if (add) additive++;
			if (texture != null) textures.add(texture);
			for (int i = 0; i < 12; i += 3) {
				reach = Math.max(reach, (float) Math.sqrt(xyz[i] * xyz[i] + xyz[i + 1] * xyz[i + 1] + xyz[i + 2] * xyz[i + 2]));
				for (int k = 0; k < 3; k++) assertTrue(Float.isFinite(xyz[i + k]), "a corner isn't finite");
			}
		}
	}

	static final Renderers.View VIEW = new Renderers.View(new float[] {-200, 0, 50}, new float[] {0, -1, 0}, new float[] {0, 0, 1});

	@Test
	void theManifestNamesTheGunsEffects() throws Exception {
		try (FxPack pack = celeste()) {
			assertEquals("particles/weapon_fx/unicorn/unicorn_tracer.vpcf", pack.effect(GUN, "m_mapWeaponInfos.primary.m_szBulletTravelTracerParticle"));
			assertNotNull(pack.effect(GUN, "m_mapWeaponInfos.primary.m_szMuzzleFlashEffectName"));
			assertNotNull(pack.effect(GUN, "m_mapWeaponInfos.primary.m_strWeaponImpactEffect"));
			assertTrue(pack.attachments.containsKey("muzzle_fx"));
			assertTrue(pack.attachments.containsKey("horn_tip_fx"));
			assertTrue(pack.ambient.contains("particles/abilities/unicorn/unicorn_ambient_horn.vpcf"));
			assertEquals(0.78f, pack.scale, 1e-4);
		}
	}

	/** Every system in the pack parses. */
	@Test
	void everySystemParses() throws Exception {
		try (FxPack pack = celeste()) {
			for (String path : pack.effects.values()) assertNotNull(pack.definition(path), path);
			FxSystem s = new FxSystem(pack, "particles/abilities/unicorn/unicorn_ball_projectile.vpcf", 1);
			assertTrue(s.children.size() > 5, "the orb's projectile has its children");
		}
	}

	/** The muzzle flash bursts, draws and is gone within a second. */
	@Test
	void muzzleFlashBurstsAndEnds() throws Exception {
		try (FxPack pack = celeste()) {
			FxSystem s = new FxSystem(pack, pack.effect(GUN, "m_mapWeaponInfos.primary.m_szMuzzleFlashEffectName"), 7);
			s.cp(0).position(0, 0, 0);
			s.cp(0).orient(1, 0, 0, 0, 0, 1);
			int most = 0;
			Count drawn = new Count();
			float t = 0;
			for (; t < 3 && !(t > 0.1f && s.finished()); t += 1 / 60f) {
				s.update(1 / 60f);
				most = Math.max(most, s.particleCount());
				Renderers.draw(s, pack, VIEW, drawn);
			}
			assertTrue(most > 0, "the flash spawns particles");
			assertTrue(drawn.quads > 0, "the flash draws");
			assertTrue(drawn.additive > 0, "it glows (additive)");
			assertTrue(t < 1.5f, "the flash is over in " + t + " s");
			assertTrue(drawn.reach < 200, "the flash stays near the muzzle (" + drawn.reach + " units)");
		}
	}

	/** The tracer rides its bullet (control point 0 moving at Celeste's 1968.5 units/s), then fades when stopped. */
	@Test
	void tracerFollowsTheBulletAndFades() throws Exception {
		try (FxPack pack = celeste()) {
			FxSystem s = new FxSystem(pack, pack.effect(GUN, "m_mapWeaponInfos.primary.m_szBulletTravelTracerParticle"), 3);
			float x = 0;
			s.cp(0).position(0, 0, 0);
			s.cp(0).orient(1, 0, 0, 0, 0, 1);
			Count flying = new Count();
			for (int i = 0; i < 30; i++) {
				x += 1968.5f / 60;
				s.cp(0).position(x, 0, 0);
				s.update(1 / 60f);
			}
			Renderers.draw(s, pack, VIEW, flying);
			assertTrue(flying.quads > 0, "the tracer draws in flight");
			// Its glow is at the bullet, not left at the muzzle.
			float[] near = {0};
			s.forEach(sys -> {
				for (Particle p : sys.particles) near[0] = Math.max(near[0], p.pos[0]);
			});
			assertTrue(near[0] > x - 200, "particles keep up with the bullet (" + near[0] + " vs " + x + ")");
			s.stop();
			float t = 0;
			for (; t < 5 && !s.finished(); t += 1 / 60f) s.update(1 / 60f);
			assertTrue(s.finished(), "the tracer ends after it stops");
			assertTrue(t < 3, "and within 3 s (" + t + ")");
		}
	}

	/** The impact plays at a hit, facing out of the wall, and ends. */
	@Test
	void impactPlaysAndEnds() throws Exception {
		try (FxPack pack = celeste()) {
			FxSystem s = new FxSystem(pack, pack.effect(GUN, "m_mapWeaponInfos.primary.m_strWeaponImpactEffect"), 11);
			s.cp(0).position(0, 0, 0);
			s.cp(0).orient(-1, 0, 0, 0, 0, 1);
			Count drawn = new Count();
			float t = 0;
			for (; t < 4 && !(t > 0.1f && s.finished()); t += 1 / 60f) {
				s.update(1 / 60f);
				Renderers.draw(s, pack, VIEW, drawn);
			}
			assertTrue(drawn.quads > 0, "the impact draws");
			assertTrue(t < 3, "the impact is over in " + t + " s");
			System.out.println("impact textures: " + drawn.textures);
		}
	}
}
