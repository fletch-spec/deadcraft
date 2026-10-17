package dev.deadcraft.client.fx;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.function.Function;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/**
 * Celeste's hand-built effects, headless: they play and end as they should, and preview strips at the game's
 * scale (build/fx-preview/celeste_*.png; rows night, night + bloom, day sky, day sky + bloom).
 */
class CelesteFxTest {
	private static final Path OUT = Path.of("build", "fx-preview");
	/** The camera looks along -x from 3 blocks away: right is +z, up is +y. */
	private static final Vec3 RIGHT = new Vec3(0, 0, 1), UP = new Vec3(0, 1, 0), BACK = new Vec3(3, 0, 0);

	/** Draws an effect into a preview frame centred on {@code centre} (Minecraft blocks). */
	static FxPreview frame(FxPack pack, Vfx v, Vec3 centre, float[] background, float bloom) {
		Vec3 eye = centre.add(BACK);
		// The preview takes Source units (x, -z, y) x 64; the view's right and up in those axes.
		Renderers.View view = new Renderers.View(new float[] {0, 0, 0}, new float[] {0, -1, 0}, new float[] {0, 0, 1});
		float[] c = {(float) (centre.x - eye.x) * 64, (float) -(centre.z - eye.z) * 64, (float) (centre.y - eye.y) * 64};
		FxPreview f = new FxPreview(pack, view, c, FxPreviewTest.GAME_PIXELS_PER_UNIT, 256, 256, background);
		float[] src = new float[12];
		v.draw(eye, RIGHT, UP, (texture, add, xyz, uv, argb, bright) -> {
			for (int i = 0; i < 4; i++) {
				src[i * 3] = xyz[i * 3] * 64;
				src[i * 3 + 1] = -xyz[i * 3 + 2] * 64;
				src[i * 3 + 2] = xyz[i * 3 + 1] * 64;
			}
			f.quad(texture, add, src, uv, argb, bright);
		});
		if (bloom > 0) f.bloom(bloom);
		return f;
	}

	/** Runs a fresh effect to each time and draws it there, over a background. */
	static FxPreview[] run(FxPack pack, Function<float[], Vfx> make, float[] clock, Vec3 centre, float[] times, float[] background, float bloom) {
		clock[0] = 0;
		Vfx v = make.apply(clock);
		FxPreview[] frames = new FxPreview[times.length];
		for (int f = 0; f < times.length; f++) {
			while (clock[0] < times[f]) {
				clock[0] += 1 / 120f;
				v.tick(1 / 120f);
			}
			frames[f] = frame(pack, v, centre, background, bloom);
		}
		return frames;
	}

	static void rows(FxPack pack, String name, Function<float[], Vfx> make, Vec3 centre, float[] times) throws Exception {
		float[] clock = {0};
		float b = FxSettings.DEFAULT_BLOOM;
		FxPreview.writeRows(new FxPreview[][] {run(pack, make, clock, centre, times, null, 0), run(pack, make, clock, centre, times, null, b),
			run(pack, make, clock, centre, times, FxPreviewTest.DAY_SKY, 0), run(pack, make, clock, centre, times, FxPreviewTest.DAY_SKY, b)},
			OUT.resolve("celeste_" + name + ".png"));
	}

	@Test
	void previews() throws Exception {
		try (FxPack pack = FxRealPackTest.celeste()) {
			Vec3 o = Vec3.ZERO;
			rows(pack, "wand_flame", c -> CelesteFx.wandFlame(pack, () -> o, 1), o, new float[] {0.1f, 0.4f, 1f, 1.5f, 2f});
			// Swung side to side, a block and a half a second.
			rows(pack, "wand_flame_swung", c -> CelesteFx.wandFlame(pack, () -> new Vec3(0, 0, 0.12 * Math.sin(c[0] * 12)), 1), o,
				new float[] {0.5f, 0.6f, 0.7f, 0.8f, 0.9f});
			Vec3 dir = new Vec3(0, 0, 1);
			rows(pack, "muzzle_flash", c -> CelesteFx.muzzleFlash(pack, o, dir, 2), o, new float[] {0.02f, 0.05f, 0.1f, 0.15f, 0.25f});
			rows(pack, "impact", c -> CelesteFx.impact(pack, o, new Vec3(1, 0, 0), 3), o, new float[] {0.02f, 0.06f, 0.12f, 0.2f, 0.3f});
			// Crossing the view at Celeste's bullet speed (30.8 blocks/s), centred where it is at each time.
			rows(pack, "tracer", c -> CelesteFx.tracer(pack, () -> new Vec3(0, 0, -0.25 + c[0] * 30.8), 4), new Vec3(0, 0, 0), new float[] {0.004f, 0.008f, 0.012f, 0.016f, 0.02f});
		}
	}

	/** The wand's flame burns while its end is there and goes out (and is dropped) once it isn't. */
	@Test
	void wandFlameBurnsThenGoesOut() throws Exception {
		try (FxPack pack = FxRealPackTest.celeste()) {
			boolean[] there = {true};
			Vfx flame = CelesteFx.wandFlame(pack, () -> there[0] ? Vec3.ZERO : null, 5);
			for (int i = 0; i < 120; i++) assertTrue(flame.tick(1 / 60f));
			int[] cards = {0};
			flame.draw(BACK, RIGHT, UP, (t, a, xyz, uv, argb, b) -> cards[0]++);
			assertTrue(cards[0] > 10, "the flame draws its glow, body, tongues and embers (" + cards[0] + " cards)");
			there[0] = false;
			float t = 0;
			while (flame.tick(1 / 60f) && t < 3) t += 1 / 60f;
			assertTrue(t < 2, "the flame is gone within 2 s of its end going away (" + t + ")");
		}
	}

	/** Flashes and impacts end on their own. */
	@Test
	void shotEffectsEnd() throws Exception {
		try (FxPack pack = FxRealPackTest.celeste()) {
			for (Vfx v : new Vfx[] {CelesteFx.muzzleFlash(pack, Vec3.ZERO, new Vec3(1, 0, 0), 1), CelesteFx.impact(pack, Vec3.ZERO, new Vec3(0, 1, 0), 2)}) {
				float t = 0;
				while (v.tick(1 / 60f) && t < 3) t += 1 / 60f;
				assertTrue(t < 1, "over in " + t + " s");
				assertFalse(v.tick(1 / 60f));
			}
		}
	}
}
