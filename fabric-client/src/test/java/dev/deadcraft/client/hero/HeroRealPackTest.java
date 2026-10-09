package dev.deadcraft.client.hero;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Checks on a real export (skipped on machines without one: run {@code tools/hero-export unicorn}).
 * They found the skirt's cloth bones flying metres away during a dash: keep every vertex near the
 * body in every clip and through the animator, and the client's skeleton equal to the exporter's.
 */
class HeroRealPackTest {
	/** No part of the hero is ever further than this from the middle of the body, metres. */
	private static final double MAX_REACH = 3.0;

	private static HeroModel celeste() throws Exception {
		Path file = HeroRenderer.heroDirectory().resolve("unicorn.dchero");
		assumeTrue(Files.exists(file), "no exported Celeste on this machine");
		return HeroModel.load(file);
	}

	/** The client builds the same skinning matrices as the exporter (its reference frame: dash_ground 15). */
	@Test
	void matchesTheExporter() throws Exception {
		HeroModel m = celeste();
		Path ref = HeroRenderer.heroDirectory().resolve("unicorn.reference");
		assumeTrue(Files.exists(ref), "no reference frame beside the export");
		ByteBuffer b = ByteBuffer.wrap(Files.readAllBytes(ref)).order(ByteOrder.LITTLE_ENDIAN);
		int n = b.getInt();
		HeroModel.Clip c = m.clips.get("dash_ground");
		HeroModel.Pose pose = m.newPose();
		pose.add(c, 15 / c.fps(), 1);
		pose.finish();
		float[] world = new float[m.nodeCount() * 12], skin = new float[m.skinnedJointCount() * 12];
		m.skin(pose, world, 0, new int[0], new float[0], skin);
		double worst = 0;
		for (int j = 0; j < n * 12; j++) worst = Math.max(worst, Math.abs(b.getFloat() - skin[j]));
		assertTrue(worst < 1e-4, "largest difference " + worst);
	}

	@Test
	void everyClipStaysOnTheBody() throws Exception {
		HeroModel m = celeste();
		float[] world = new float[m.nodeCount() * 12], skin = new float[m.skinnedJointCount() * 12];
		for (HeroModel.Clip c : m.clips.values()) {
			for (int f = 0; f < c.frames(); f++) {
				HeroModel.Pose pose = m.newPose();
				pose.add(c, f / c.fps(), 1);
				pose.finish();
				m.skin(pose, world, 0, new int[0], new float[0], skin);
				double reach = reach(m, skin);
				assertTrue(reach < MAX_REACH, c.name() + " frame " + f + " reaches " + reach + " m");
			}
		}
	}

	/** Scripted play through the animator: idle, run, dash, stop, then turning on the spot. */
	@Test
	void animatorStaysOnTheBody() throws Exception {
		HeroModel m = celeste();
		HeroAnimator anim = new HeroAnimator(m);
		float dt = 1 / 120f;
		for (int i = 0; i < 120 * 6; i++) {
			float t = i * dt;
			double forward = t < 1 ? 0 : t < 4 ? 6 : 0;
			var in = new HeroAnimator.Input(forward, 0, 0, true, 86, 112, HeroAnimator.Wall.NONE);
			if (Math.abs(t - 2) < dt / 2) anim.ability("citadel_ability_dash", in, Double.NaN);
			anim.setLook(t > 4.5f ? (float) Math.sin(t * 3) * 70 : 0);
			anim.setTurnRate(t > 4.5f ? (float) Math.cos(t * 3) * 210 : 0);
			double reach = reach(m, anim.update(in, dt));
			assertTrue(reach < MAX_REACH, "at " + t + " s (" + anim.state() + ") reaches " + reach + " m");
		}
	}

	/** The furthest skinned vertex from the middle of the body (1.2 m up). */
	private static double reach(HeroModel m, float[] skin) {
		double far = 0;
		for (int v = 0, nv = m.vertexCount(); v < nv; v++) {
			float x = m.positions[v * 3], y = m.positions[v * 3 + 1], z = m.positions[v * 3 + 2];
			double px = 0, py = 0, pz = 0;
			for (int k = 0; k < 4; k++) {
				float w = m.weights[v * 4 + k];
				if (w <= 0) continue;
				int b = m.joints[v * 4 + k] * 12;
				px += w * (skin[b] * x + skin[b + 1] * y + skin[b + 2] * z + skin[b + 3]);
				py += w * (skin[b + 4] * x + skin[b + 5] * y + skin[b + 6] * z + skin[b + 7]);
				pz += w * (skin[b + 8] * x + skin[b + 9] * y + skin[b + 10] * z + skin[b + 11]);
			}
			far = Math.max(far, Math.sqrt(px * px + (py - 1.2) * (py - 1.2) + pz * pz));
		}
		return far;
	}
}
