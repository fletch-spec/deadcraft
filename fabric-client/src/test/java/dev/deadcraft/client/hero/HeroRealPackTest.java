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
				HeroModel.Pose pose = sample(m, c, f / c.fps());
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

	/** Walk off an edge, fall a second, land standing still: the landing impact and the fades around it. */
	@Test
	void fallAndLandStaysOnTheBody() throws Exception {
		HeroModel m = celeste();
		HeroAnimator anim = new HeroAnimator(m);
		float dt = 1 / 120f;
		StringBuilder trace = new StringBuilder();
		for (int i = 0; i < 120 * 3; i++) {
			float t = i * dt;
			boolean air = t > 0.5f && t < 1.45f;
			var in = new HeroAnimator.Input(0, 0, air ? (t < 1 ? 4 : -6) : 0, !air, 86, 112, HeroAnimator.Wall.NONE);
			// A standing jump, as played in game: jump clip, a moment of fall, then the landing.
			if (Math.abs(t - 0.5f) < dt / 2) anim.ability("citadel_ability_jump", in, Double.NaN);
			double reach = reach(m, anim.update(in, dt));
			trace.append(String.format("%.3f %s %.2f%n", t, anim.state(), reach));
			assertTrue(reach < MAX_REACH, "at " + t + " s (" + anim.state() + ") reaches " + reach + " m\n" + trace);
		}
	}

	/** A clip's pose at a time; an additive clip layered on the standing idle, as the animator plays it. */
	private static HeroModel.Pose sample(HeroModel m, HeroModel.Clip c, float time) {
		HeroModel.Pose pose = m.newPose();
		if (!c.additive()) {
			pose.add(c, time, 1);
			pose.finish();
			return pose;
		}
		pose.add(m.clips.get("out_of_combat_stand_idle"), time, 1);
		pose.finish();
		pose.addLayer(c, time, 1, m.subtree("spine_0", "leg_upper_L", "leg_upper_R"));
		return pose;
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

	/** The skirt through the animator: a standing jump, a fall and the landing, fades included. */
	@Test
	void skirtStaysOnTheHipsThroughALanding() throws Exception {
		HeroModel m = celeste();
		HeroAnimator anim = new HeroAnimator(m);
		int pelvis = m.node("pelvis");
		float dt = 1 / 240f;
		StringBuilder trace = new StringBuilder();
		double worst = 0;
		for (int i = 0; i < 240 * 3; i++) {
			float t = i * dt;
			boolean air = t > 0.5f && t < 1.45f;
			var in = new HeroAnimator.Input(0, 0, air ? (t < 1 ? 4 : -6) : 0, !air, 86, 112, HeroAnimator.Wall.NONE);
			if (Math.abs(t - 0.5f) < dt / 2) anim.ability("citadel_ability_jump", in, Double.NaN);
			float[] skin = anim.update(in, dt);
			double d = skirtReach(m, skin, anim.lastWorld(), pelvis);
			worst = Math.max(worst, d);
			if (i % 6 == 0) trace.append(String.format("%.3f %s %.2f%n", t, anim.state(), d));
		}
		Files.writeString(Path.of("build", "skirt-landing.txt"), trace);
		assertTrue(worst < SKIRT_REACH, "skirt " + worst + " m from the hips during the landing; see build/skirt-landing.txt");
	}

	/** In every clip (layered ones too) and with the upper body turned, the hand holds the wand at its grip. */
	@Test
	void wandStaysInTheHand() throws Exception {
		HeroModel m = celeste();
		HeroModel.Rig rig = new HeroModel.Rig();
		rig.twistNodes = new int[] {m.node("spine_1"), m.node("spine_2"), m.node("spine_3"), m.node("neck_0"), m.node("head")};
		rig.twistShares = new float[] {0.15f, 0.2f, 0.2f, 0.2f, 0.25f};
		rig.follower = m.node("weaponPivot");
		rig.grip = m.node("weaponHand_R");
		rig.leader = m.node("hand_R");
		rig.twist = (float) Math.toRadians(35);
		rig.followerTwist = rig.twist * 0.55f;
		float[] world = new float[m.nodeCount() * 12], skin = new float[m.skinnedJointCount() * 12];
		for (HeroModel.Clip c : m.clips.values()) {
			for (int f = 0; f < c.frames(); f += 3) {
				m.skin(sample(m, c, f / c.fps()), world, rig, skin);
				double d = Math.sqrt(sq(world[rig.grip * 12 + 3] - world[rig.leader * 12 + 3]) + sq(world[rig.grip * 12 + 7] - world[rig.leader * 12 + 7])
					+ sq(world[rig.grip * 12 + 11] - world[rig.leader * 12 + 11]));
				assertTrue(d < 1e-3, c.name() + " frame " + f + ": hand " + d + " m from the wand's grip");
			}
		}
	}

	/** Running, the swung ponytail ends up behind the back instead of hanging into it. */
	@Test
	void hairSwingsBehindWhenRunning() throws Exception {
		HeroModel m = celeste();
		HeroModel.Rig rig = new HeroModel.Rig();
		rig.swingNodes = new int[] {m.node("ponytail_0"), m.node("ponytail_5"), m.node("ponytail_10")};
		rig.swingShares = new float[] {0.45f, 0.3f, 0.25f};
		rig.swing = (float) Math.toRadians(40);
		float[] world = new float[m.nodeCount() * 12], skin = new float[m.skinnedJointCount() * 12];
		m.skin(sample(m, m.clips.get("out_of_combat_run_n"), 0.2f), world, rig, skin);
		int end = m.node("ponytail_20"), pelvis = m.node("pelvis");  // the last joint in the pack (end joints move no vertex)
		// The model faces +Z: behind the back is smaller z.
		assertTrue(world[end * 12 + 11] < world[pelvis * 12 + 11] - 0.15, "ponytail end z " + world[end * 12 + 11] + ", pelvis z " + world[pelvis * 12 + 11]);
	}

	private static double sq(double v) {
		return v * v;
	}

	/** The skirt (vertices hanging mainly from cloth bones) stays near the hips in every clip and frame. */
	@Test
	void skirtStaysOnTheHips() throws Exception {
		HeroModel m = celeste();
		float[] world = new float[m.nodeCount() * 12], skin = new float[m.skinnedJointCount() * 12];
		int pelvis = m.node("pelvis");
		StringBuilder report = new StringBuilder();
		double worstAll = 0;
		String worstAt = "";
		for (HeroModel.Clip c : m.clips.values()) {
			double worst = 0;
			for (int f = 0; f < c.frames(); f++) {
				HeroModel.Pose pose = sample(m, c, f / c.fps());
				m.skin(pose, world, 0, new int[0], new float[0], skin);
				double d = skirtReach(m, skin, world, pelvis);
				if (d > worst) worst = d;
				if (d > worstAll) {
					worstAll = d;
					worstAt = c.name() + " frame " + f;
				}
			}
			report.append(String.format("%-28s skirt reach %.2f m%n", c.name(), worst));
		}
		Files.writeString(Path.of("build", "skirt-reach.txt"), report);
		assertTrue(worstAll < SKIRT_REACH, "skirt " + worstAll + " m from the hips at " + worstAt);
	}

	private static final double SKIRT_REACH = 0.8;  // 0.59 at most once fixed; the landing spike was over 1 m

	private static double skirtReach(HeroModel m, float[] skin, float[] world, int pelvis) {
		double hx = world[pelvis * 12 + 3], hy = world[pelvis * 12 + 7], hz = world[pelvis * 12 + 11], far = 0;
		for (int v = 0, nv = m.vertexCount(); v < nv; v++) {
			int main = 0;
			for (int k = 1; k < 4; k++) if (m.weights[v * 4 + k] > m.weights[v * 4 + main]) main = k;
			if (!m.nodeNames[m.skinNodes[m.joints[v * 4 + main]]].startsWith("$cloth")) continue;
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
			far = Math.max(far, Math.sqrt((px - hx) * (px - hx) + (py - hy) * (py - hy) + (pz - hz) * (pz - hz)));
		}
		return far;
	}
}
