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

	/** With the upper body turned towards the camera, the wand stays in the right hand. */
	@Test
	void wandStaysInTheHand() throws Exception {
		HeroModel m = celeste();
		int weapon = m.node("weaponPivot"), hand = m.node("hand_R");
		int[] look = {m.node("spine_1"), m.node("spine_2"), m.node("spine_3"), m.node("neck_0"), m.node("head")};
		float[] shares = {0.15f, 0.2f, 0.2f, 0.2f, 0.25f};
		HeroModel.Pose pose = m.newPose();
		pose.add(m.clips.get("out_of_combat_stand_idle"), 0.3f, 1);
		pose.finish();
		float[] world = new float[m.nodeCount() * 12], skin = new float[m.skinnedJointCount() * 12];
		m.skin(pose, world, 0, look, shares, weapon, hand, skin);
		double straight = distance(world, weapon, hand);
		m.skin(pose, world, (float) Math.toRadians(60), look, shares, weapon, hand, skin);
		double turned = distance(world, weapon, hand);
		assertTrue(Math.abs(turned - straight) < 1e-3, "wand to hand " + straight + " m straight, " + turned + " m turned");
	}

	private static double distance(float[] world, int a, int b) {
		double dx = world[a * 12 + 3] - world[b * 12 + 3], dy = world[a * 12 + 7] - world[b * 12 + 7], dz = world[a * 12 + 11] - world[b * 12 + 11];
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
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
				HeroModel.Pose pose = m.newPose();
				pose.add(c, f / c.fps(), 1);
				pose.finish();
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
