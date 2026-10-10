package dev.deadcraft.client.hero;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.deadcraft.client.fx.FxPack;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The model's attachment points (from the effects pack) land on the drawn model: effects such as the horn
 * sparkle and the wand's muzzle flash are placed by them (needs both exports: hero-export unicorn, --fx).
 */
class HeroAttachmentTest {
	@Test
	void attachmentsSitOnTheModel() throws Exception {
		Path dir = HeroRenderer.heroDirectory();
		assumeTrue(Files.exists(dir.resolve("unicorn.dchero")) && Files.exists(dir.resolve("unicorn.dcfx")), "no exported Celeste");
		HeroModel m = HeroModel.load(dir.resolve("unicorn.dchero"));
		try (FxPack fx = FxPack.load(dir.resolve("unicorn.dcfx"))) {
			HeroAnimator anim = new HeroAnimator(m);
			float[] skin = null;
			for (int i = 0; i < 60; i++) skin = anim.update(new HeroAnimator.Input(0, 0, 0, true, 86, 112, HeroAnimator.Wall.NONE), 1 / 60f);
			float[] world = anim.lastWorld();
			float[] verts = skinned(m, skin);
			StringBuilder report = new StringBuilder();
			double worst = 0;
			for (String name : new String[] {"horn_tip_fx", "horn_base_fx", "weapon_top_fx", "weapon_bot_fx", "muzzle_fx", "palm_L"}) {
				FxPack.Attachment raw = fx.attachments.get(name);
				assumeTrue(raw != null, name);
				FxPack.Attachment a = fx.onModel(raw, bone -> m.node(bone) >= 0);
				assertTrue(a != null, name + " has a bone on the model");
				int n = m.node(a.bone());
				if (n < 0) {
					StringBuilder like = new StringBuilder();
					for (String nn : m.nodeNames) if (nn.contains("head") || nn.contains("weapon") || nn.contains("horn")) like.append(nn).append(' ');
					assertTrue(false, "bone " + a.bone() + " isn't in the model; similar: " + like);
				}
				double[] p = point(world, n, a.offset());
				double near = nearest(verts, p);
				report.append(String.format("%s (%s): %.3f m from the mesh; at %.2f %.2f %.2f%n", name, a.bone(), near, p[0], p[1], p[2]));
				if (!name.equals("muzzle_fx")) worst = Math.max(worst, near);
			}
			System.out.println(report);
			// Each point is on (or just off) the mesh it belongs to; a wrong frame or unit puts them decimetres away.
			assertTrue(worst < 0.06, "attachments off the mesh:\n" + report);
			int head = m.node("head");
			FxPack.Attachment horn = fx.onModel(fx.attachments.get("horn_tip_fx"), bone -> m.node(bone) >= 0);
			double[] tip = point(world, m.node(horn.bone()), horn.offset());
			assertTrue(tip[1] > world[head * 12 + 7], "the horn tip is above the head's joint:\n" + report);
		}
	}

	static double[] point(float[] w, int n, float[] offsetUnits) {
		double ox = offsetUnits[0] * 0.0254, oy = offsetUnits[1] * 0.0254, oz = offsetUnits[2] * 0.0254;
		int b = n * 12;
		return new double[] {w[b] * ox + w[b + 1] * oy + w[b + 2] * oz + w[b + 3], w[b + 4] * ox + w[b + 5] * oy + w[b + 6] * oz + w[b + 7],
			w[b + 8] * ox + w[b + 9] * oy + w[b + 10] * oz + w[b + 11]};
	}

	static float[] skinned(HeroModel m, float[] mats) {
		float[] out = new float[m.vertexCount() * 3];
		for (int v = 0; v < m.vertexCount(); v++) {
			float x = m.positions[v * 3], y = m.positions[v * 3 + 1], z = m.positions[v * 3 + 2];
			for (int k = 0; k < 4; k++) {
				float wt = m.weights[v * 4 + k];
				if (wt <= 0) continue;
				int b = m.joints[v * 4 + k] * 12;
				out[v * 3] += wt * (mats[b] * x + mats[b + 1] * y + mats[b + 2] * z + mats[b + 3]);
				out[v * 3 + 1] += wt * (mats[b + 4] * x + mats[b + 5] * y + mats[b + 6] * z + mats[b + 7]);
				out[v * 3 + 2] += wt * (mats[b + 8] * x + mats[b + 9] * y + mats[b + 10] * z + mats[b + 11]);
			}
		}
		return out;
	}

	static double nearest(float[] verts, double[] p) {
		double best = Double.MAX_VALUE;
		for (int i = 0; i < verts.length; i += 3) {
			double dx = verts[i] - p[0], dy = verts[i + 1] - p[1], dz = verts[i + 2] - p[2];
			best = Math.min(best, dx * dx + dy * dy + dz * dz);
		}
		return Math.sqrt(best);
	}
}
