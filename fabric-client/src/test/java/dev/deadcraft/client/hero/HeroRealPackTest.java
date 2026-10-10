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
	/** Except the wand: Celeste tosses it about 4 m up while reloading, and catches it. */
	private static final double MAX_WAND_REACH = 5.0;

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
		boolean[] wand = m.subtree("weaponPivot");
		float[] world = new float[m.nodeCount() * 12], skin = new float[m.skinnedJointCount() * 12];
		for (HeroModel.Clip c : m.clips.values()) {
			for (int f = 0; f < c.frames(); f++) {
				HeroModel.Pose pose = sample(m, c, f / c.fps());
				m.skin(pose, world, 0, new int[0], new float[0], skin);
				double reach = reach(m, skin, wand, false), wandReach = reach(m, skin, wand, true);
				assertTrue(reach < MAX_REACH, c.name() + " frame " + f + " reaches " + reach + " m");
				assertTrue(wandReach < MAX_WAND_REACH, c.name() + " frame " + f + ": the wand reaches " + wandReach + " m");
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

	/**
	 * The landing drops the hips as it bends the knees (its pelvis channel was ignored: the knees bent
	 * under hips that stayed put, lifting the feet off the ground).
	 */
	@Test
	void landingDropsTheHips() throws Exception {
		HeroModel m = celeste();
		HeroAnimator anim = new HeroAnimator(m);
		float dt = 1 / 120f;
		int pelvis = m.node("pelvis"), ankleL = m.node("ankle_L"), ankleR = m.node("ankle_R");
		double standingHips = 0, standingFeet = 0, lowestHips = 9, highestFeet = -9;
		for (int i = 0; i < 120 * 3; i++) {
			float t = i * dt;
			boolean air = t > 0.5f && t < 1.45f;
			anim.update(new HeroAnimator.Input(0, 0, air ? -6 : 0, !air, 86, 112, HeroAnimator.Wall.NONE), dt);
			float[] w = anim.lastWorld();
			double hips = w[pelvis * 12 + 7], feet = Math.min(w[ankleL * 12 + 7], w[ankleR * 12 + 7]);
			if (t < 0.5f) {
				standingHips = hips;
				standingFeet = feet;
			}
			if (anim.state() == HeroAnimator.State.LAND) {
				lowestHips = Math.min(lowestHips, hips);
				highestFeet = Math.max(highestFeet, feet);
			}
		}
		assertTrue(standingHips - lowestHips > 0.12, "the hips only dropped " + (standingHips - lowestHips) + " m");
		assertTrue(highestFeet - standingFeet < 0.12, "the feet rose " + (highestFeet - standingFeet) + " m off the ground");
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
		return reach(m, skin, null, false);
	}

	/** Reach of the vertices hung mainly from nodes in {@code part} ({@code inPart}) or of the others; all if null. */
	private static double reach(HeroModel m, float[] skin, boolean[] part, boolean inPart) {
		double far = 0;
		for (int v = 0, nv = m.vertexCount(); v < nv; v++) {
			if (part != null && part[m.skinNodes[m.joints[v * 4]]] != inPart) continue;
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

	/**
	 * The reload tosses the wand up and catches it, through the animator with the wand placed in the
	 * hand and the upper body twisted: high over the head mid-reload, back in the hand afterwards.
	 */
	@Test
	void reloadTossesTheWandAndCatchesIt() throws Exception {
		HeroModel m = celeste();
		HeroAnimator anim = new HeroAnimator(m);
		int wand = m.node("weapon"), hand = m.node("hand_R"), head = m.node("head");
		double highest = -9, endGap = 9;
		for (int i = 0; i < 120 * 3; i++) {
			long buttons = i >= 12 && i < 24 ? HeroAnimator.RELOAD : 0;
			anim.setLook(30);
			anim.update(new HeroAnimator.Input(0, 0, 0, true, 86, 112, HeroAnimator.Wall.NONE, 0, buttons, false), 1 / 120f);
			float[] w = anim.lastWorld();
			highest = Math.max(highest, w[wand * 12 + 7] - w[head * 12 + 7]);
			endGap = Math.sqrt(sq(w[wand * 12 + 3] - w[hand * 12 + 3]) + sq(w[wand * 12 + 7] - w[hand * 12 + 7]) + sq(w[wand * 12 + 11] - w[hand * 12 + 11]));
		}
		assertTrue(highest > 1.5, "the wand only rose " + highest + " m above the head");
		assertTrue(endGap < 0.3, "the wand ended " + endGap + " m from the hand");
	}

	/**
	 * Reloading or shooting while running keeps the run's torso motion. Played as they are, the standing
	 * clips set the spine's joints to standing values over the running hips: the chest swung 25 to 45 deg
	 * off the run's, back and forth. Now their motion is added to the run's.
	 */
	@Test
	void actionsWhileRunningKeepTheTorsoMoving() throws Exception {
		HeroModel m = celeste();
		double[] plain = chestYaw(m, 0, false), reloading = chestYaw(m, HeroAnimator.RELOAD, false), shooting = chestYaw(m, 0, true);
		double reloadOff = 0, shootOff = 0;
		for (int i = 0; i < plain.length; i++) {
			reloadOff += Math.abs(reloading[i] - plain[i]) / plain.length;
			shootOff += Math.abs(shooting[i] - plain[i]) / plain.length;
		}
		assertTrue(shootOff < 10, "shooting while running turns the chest " + shootOff + " deg off the run's on average");
		assertTrue(reloadOff < 18, "reloading while running turns the chest " + reloadOff + " deg off the run's on average");
	}

	/** Which way the chest faces (degrees about the vertical), each frame of a second of running after a second to settle. */
	private static double[] chestYaw(HeroModel m, long pressAtOneSecond, boolean shooting) {
		HeroAnimator anim = new HeroAnimator(m);
		int chest = m.node("spine_3");
		double[] yaw = new double[120];
		for (int i = 0; i < 240; i++) {
			float t = i / 120f;
			long buttons = Math.abs(t - 1) < 0.02 ? pressAtOneSecond : 0;
			if (shooting && i % 30 == 0) anim.shot();
			anim.update(new HeroAnimator.Input(6, 0, 0, true, 86, 112, HeroAnimator.Wall.NONE, 0, buttons | HeroAnimator.ALT_FIRE, false), 1 / 120f);
			if (i < 120) continue;
			float[] w = anim.lastWorld();
			yaw[i - 120] = Math.toDegrees(Math.atan2(w[chest * 12 + 8], w[chest * 12]));
		}
		return yaw;
	}

	/**
	 * Single shots a little apart don't snap: the shooting pose holds between them. It used to end half a
	 * second after each shot and start over at the next, so the arm dropped back and jerked up every shot.
	 */
	@Test
	void singleShotsDoNotSnap() throws Exception {
		HeroModel m = celeste();
		HeroAnimator anim = new HeroAnimator(m);
		float weakest = 1;
		String at = "";
		for (int i = 0; i < 120 * 5; i++) {
			if (i % 84 == 0 && i < 120 * 4) anim.shot();  // a tap every 0.7 s for 4 s
			anim.update(new HeroAnimator.Input(0, 0, 0, true, 86, 112, HeroAnimator.Wall.NONE, 0, 0, false), 1 / 120f);
			if (i > 30 && i < 120 * 4 && anim.actionWeight() < weakest) {
				weakest = anim.actionWeight();
				at = (i / 120f) + " s (" + anim.debugLine() + ")";
			}
		}
		assertTrue(weakest > 0.95f, "the shooting pose faded to " + weakest + " between shots at " + at);
	}

	/**
	 * Abilities start on the button press when they're ready (the event came up to a second later, the
	 * orb's raise visibly late), not when on cooldown; the orb's event then doesn't start it over.
	 */
	@Test
	void abilitiesStartOnThePress() throws Exception {
		HeroModel m = celeste();
		HeroAnimator anim = new HeroAnimator(m);
		var press = new HeroAnimator.Input(0, 0, 0, true, 86, 112, HeroAnimator.Wall.NONE, 0, HeroAnimator.abilityButton(0), false, 0b1110);
		anim.update(press, 1 / 120f);
		assertTrue(!anim.debugLine().contains("CAST"), "started Radiant Blast while on cooldown: " + anim.debugLine());
		anim = new HeroAnimator(m);
		anim.update(new HeroAnimator.Input(0, 0, 0, true, 86, 112, HeroAnimator.Wall.NONE, 0, HeroAnimator.abilityButton(3), false, 0b1111), 1 / 120f);
		assertTrue(anim.debugLine().contains("ORB ability_unicorn_dazzlingorb_start"), "the orb didn't start on the press: " + anim.debugLine());
		var idle = new HeroAnimator.Input(0, 0, 0, true, 86, 112, HeroAnimator.Wall.NONE, 0, 0, false);
		for (int i = 0; i < 100; i++) anim.update(idle, 1 / 120f);
		anim.ability("ability_unicorn_dazzlingorb", idle, Double.NaN);
		var channel = new HeroAnimator.Input(0, 0, 0, true, 86, 112, HeroAnimator.Wall.NONE, 0, 0, true);
		for (int i = 0; i < 240; i++) anim.update(channel, 1 / 120f);
		assertTrue(anim.debugLine().contains("ability_unicorn_dazzlingorb_loop"), "not holding the orb: " + anim.debugLine());
		anim.update(idle, 1 / 120f);
		assertTrue(anim.debugLine().contains("ability_unicorn_dazzlingorb_end"), "the orb wasn't thrown on release: " + anim.debugLine());
	}

	/**
	 * Reloads follow the weapon: an empty magazine reloads with no button (holding fire never showed one),
	 * the clip follows Deadlock's progress, and in the air it's the running reload's motion (the in-air
	 * clip looked broken over a jump).
	 */
	@Test
	void reloadFollowsTheWeapon() throws Exception {
		HeroModel m = celeste();
		HeroAnimator anim = new HeroAnimator(m);
		float dt = 1 / 120f;
		String during = "", after = "";
		for (int i = 0; i < 120 * 3; i++) {
			float t = i * dt;
			boolean air = t > 0.5f;
			float reload = t > 0.6f && t < 2.1f ? (t - 0.6f) / 1.5f : -1;
			anim.update(new HeroAnimator.Input(0, 0, air ? 1 : 0, !air, 86, 112, HeroAnimator.Wall.NONE, 0, HeroAnimator.ATTACK, false, 0xF, reload), dt);
			if (Math.abs(t - 1.35f) < dt / 2) during = anim.debugLine();
			if (Math.abs(t - 2.6f) < dt / 2) after = anim.debugLine();
		}
		assertTrue(during.contains("RELOAD reload_run 0.75 s"), "mid-reload in the air: " + during);
		assertTrue(!after.contains("RELOAD"), "still reloading after the weapon finished: " + after);
	}

	/**
	 * Melee from its button and Deadlock's events. The press starts the heavy wind-up at once (it used to
	 * start a quarter second in, and the hit cut it short); a tap's event turns it into a quick melee. Held,
	 * the hit's event plays the hit; kept held, Deadlock strikes again each cooldown: she stands between,
	 * winding up again just before the next hit.
	 */
	@Test
	void meleeFollowsItsButton() throws Exception {
		HeroModel m = celeste();
		float dt = 1 / 120f;
		var idle = new HeroAnimator.Input(0, 0, 0, true, 86, 112, HeroAnimator.Wall.NONE, 0, 0, false);
		var held = new HeroAnimator.Input(0, 0, 0, true, 86, 112, HeroAnimator.Wall.NONE, 0, HeroAnimator.MELEE, false);
		HeroAnimator anim = new HeroAnimator(m);
		anim.update(held, dt);
		assertTrue(anim.debugLine().contains("MELEE melee_start"), "the press: " + anim.debugLine());
		for (int i = 0; i < 5; i++) anim.update(held, dt);
		anim.ability("ability_melee_unicorn", held, Double.NaN);
		for (int i = 0; i < 5; i++) anim.update(held, dt);
		anim.update(idle, dt);
		assertTrue(anim.debugLine().contains("MELEE melee_quick_1"), "a tap: " + anim.debugLine());

		anim = new HeroAnimator(m);
		for (int i = 0; i < 60; i++) anim.update(held, dt);
		assertTrue(anim.debugLine().contains("MELEE melee_start"), "held half a second: " + anim.debugLine());
		anim.ability("ability_melee_unicorn", held, Double.NaN);
		anim.update(held, dt);
		assertTrue(anim.debugLine().contains("MELEE melee_hit"), "the hit: " + anim.debugLine());
		for (int i = 0; i < 120; i++) anim.update(held, dt);
		assertTrue(anim.debugLine().contains("action NONE"), "still held, waiting for the cooldown: not standing: " + anim.debugLine());
		for (int i = 0; i < 70; i++) anim.update(held, dt);
		assertTrue(anim.debugLine().contains("MELEE melee_start"), "no wind-up before the next hit: " + anim.debugLine());
		anim.ability("ability_melee_unicorn", held, Double.NaN);
		anim.update(held, dt);
		assertTrue(anim.debugLine().contains("MELEE melee_hit"), "the second hit: " + anim.debugLine());
	}

	/** Spamming melee: Deadlock strikes about every 0.8 s whatever the taps; one quick melee per strike, not per tap. */
	@Test
	void spammedMeleeFollowsTheStrikes() throws Exception {
		HeroModel m = celeste();
		HeroAnimator anim = new HeroAnimator(m);
		float dt = 1 / 120f;
		int strikes = 0, starts = 0;
		String clip = "";
		for (int i = 0; i < 120 * 4; i++) {
			boolean down = i % 24 < 6;  // a tap every 0.2 s
			var in = new HeroAnimator.Input(0, 0, 0, true, 86, 112, HeroAnimator.Wall.NONE, 0, down ? HeroAnimator.MELEE : 0, false);
			if (i % 96 == 3) {  // a strike every 0.8 s, its event during a press
				anim.ability("ability_melee_unicorn", in, Double.NaN);
				strikes++;
			}
			anim.update(in, dt);
			String now = anim.debugLine().replaceAll(".*action MELEE (melee_quick_\\d).*", "$1");
			if (now.startsWith("melee_quick") && !now.equals(clip)) starts++;
			clip = now;
		}
		assertTrue(starts == strikes, starts + " quick melees for " + strikes + " strikes");
	}

	/**
	 * Spamming crouch while moving: Deadlock slows the hero to a crawl (~0.4 blocks/s) and the eye bobs up
	 * for a few hundredths of a second at a time. She stays crouched (the legs move at the crawl's pace).
	 */
	@Test
	void crouchSpamStaysCrouched() throws Exception {
		HeroModel m = celeste();
		HeroAnimator anim = new HeroAnimator(m);
		float dt = 1 / 120f;
		for (int i = 0; i < 120 * 3; i++) {
			boolean up = i > 60 && i % 17 < 3;  // the eye up for 0.025 s every 0.14 s
			anim.update(new HeroAnimator.Input(i < 60 ? 5 : 0.4, 0, 0, true, up ? 86 : 55, up ? 112 : 64, HeroAnimator.Wall.NONE), dt);
			if (i > 120) assertTrue(anim.state() == HeroAnimator.State.CROUCH_RUN, "stood up at " + i / 120f + " s: " + anim.debugLine());
		}
	}

	/**
	 * Melee in the air: the whole body plays the clip (it spins the hips 153 deg and swings the legs).
	 */
	@Test
	void airMeleeIsWholeBody() throws Exception {
		HeroModel m = celeste();
		HeroAnimator anim = new HeroAnimator(m);
		float dt = 1 / 120f;
		var air = new HeroAnimator.Input(4, 0, 1, false, 86, 112, HeroAnimator.Wall.NONE, 0, 0, false);
		var airHeld = new HeroAnimator.Input(4, 0, 1, false, 86, 112, HeroAnimator.Wall.NONE, 0, HeroAnimator.MELEE, false);
		for (int i = 0; i < 60; i++) anim.update(air, dt);
		for (int i = 0; i < 3; i++) anim.update(airHeld, dt);
		anim.ability("ability_melee_unicorn", airHeld, Double.NaN);
		for (int i = 0; i < 3; i++) anim.update(airHeld, dt);
		for (int i = 0; i < 60; i++) anim.update(air, dt);
		assertTrue(anim.debugLine().contains("MELEE melee_quick_in_air_1") && anim.debugLine().contains("legs 1.00"), "mid air melee: " + anim.debugLine());

	}

	/**
	 * Standing with the camera a little up or down, the idle has no snap: the aim clips looped every 2 s,
	 * out of step with the idle, and jumped back at each loop. No frame steps much more than the idle's own.
	 */
	@Test
	void idleWithPitchDoesNotSnap() throws Exception {
		HeroModel m = celeste();
		double level = worstHeadStep(m, 0), looking = worstHeadStep(m, 25);
		assertTrue(looking < level * 1.5 + 0.001, "looking down 25 deg the head jumps " + looking + " m in a frame, against " + level + " m level");
	}

	private static double worstHeadStep(HeroModel m, float pitch) {
		HeroAnimator anim = new HeroAnimator(m);
		int head = m.node("head");
		double worst = 0, px = 0, py = 0, pz = 0;
		for (int i = 0; i < 120 * 6; i++) {
			anim.update(new HeroAnimator.Input(0, 0, 0, true, 86, 112, HeroAnimator.Wall.NONE, pitch, 0, false), 1 / 120f);
			float[] w = anim.lastWorld();
			double x = w[head * 12 + 3], y = w[head * 12 + 7], z = w[head * 12 + 11];
			if (i > 60) worst = Math.max(worst, Math.sqrt(sq(x - px) + sq(y - py) + sq(z - pz)));
			px = x; py = y; pz = z;
		}
		return worst;
	}

	/** Standing up for real after a crouch takes effect at once (the hold for crouch spam kept her down 0.2 s). */
	@Test
	void standingUpIsPrompt() throws Exception {
		HeroModel m = celeste();
		HeroAnimator anim = new HeroAnimator(m);
		for (int i = 0; i < 60; i++) anim.update(new HeroAnimator.Input(3, 0, 0, true, 86, 112, HeroAnimator.Wall.NONE), 1 / 120f);
		for (int i = 0; i < 120; i++) anim.update(new HeroAnimator.Input(3, 0, 0, true, 55, 64, HeroAnimator.Wall.NONE), 1 / 120f);
		assertTrue(anim.state() == HeroAnimator.State.CROUCH_RUN, "crouched: " + anim.state());
		for (int i = 0; i < 12; i++) anim.update(new HeroAnimator.Input(3, 0, 0, true, 86, 112, HeroAnimator.Wall.NONE), 1 / 120f);
		assertTrue(anim.state() == HeroAnimator.State.RUN, "0.1 s after standing up: " + anim.state());
	}

	/** Reloading standing still leaves the legs in the idle (the clip shuffled the left foot and lifted the knee). */
	@Test
	void standingReloadKeepsTheFeet() throws Exception {
		HeroModel m = celeste();
		HeroAnimator anim = new HeroAnimator(m), idle = new HeroAnimator(m);
		int ankle = m.node("ankle_L");
		double worst = 0;
		for (int i = 0; i < 120 * 3; i++) {
			float t = i / 120f;
			float reload = t > 0.5f && t < 2.5f ? (t - 0.5f) / 2 : -1;
			anim.update(new HeroAnimator.Input(0, 0, 0, true, 86, 112, HeroAnimator.Wall.NONE, 0, HeroAnimator.ALT_FIRE, false, 0xF, reload), 1 / 120f);
			idle.update(new HeroAnimator.Input(0, 0, 0, true, 86, 112, HeroAnimator.Wall.NONE, 0, HeroAnimator.ALT_FIRE, false, 0xF, -1), 1 / 120f);
			float[] a = anim.lastWorld(), b = idle.lastWorld();
			worst = Math.max(worst, Math.sqrt(sq(a[ankle * 12 + 3] - b[ankle * 12 + 3]) + sq(a[ankle * 12 + 7] - b[ankle * 12 + 7]) + sq(a[ankle * 12 + 11] - b[ankle * 12 + 11])));
		}
		assertTrue(worst < 0.03, "the left foot moved " + worst + " m from the idle's during a standing reload");
	}

	/** The heavy hit ends after the strike (its tail is two settling steps Deadlock doesn't show). */
	@Test
	void heavyHitEndsAfterTheStrike() throws Exception {
		HeroModel m = celeste();
		HeroAnimator anim = new HeroAnimator(m);
		var held = new HeroAnimator.Input(0, 0, 0, true, 86, 112, HeroAnimator.Wall.NONE, 0, HeroAnimator.MELEE, false);
		var idle = new HeroAnimator.Input(0, 0, 0, true, 86, 112, HeroAnimator.Wall.NONE, 0, 0, false);
		for (int i = 0; i < 60; i++) anim.update(held, 1 / 120f);
		anim.ability("ability_melee_unicorn", held, Double.NaN);
		for (int i = 0; i < 60; i++) anim.update(idle, 1 / 120f);
		assertTrue(anim.debugLine().contains("action NONE"), "half a second after the hit: " + anim.debugLine());
	}

	/**
	 * Shooting on the run, the wand stays in the hand as it's held standing: the arms followed the running
	 * torso and the wand (hanging off the root) kept the clip's angle, outside the hand.
	 */
	@Test
	void wandStaysInTheHandShootingOnTheRun() throws Exception {
		HeroModel m = celeste();
		float[] standing = wandInHand(m, 0), running = wandInHand(m, 6);
		double worst = 0;
		for (int i = 0; i < running.length; i += 9) {
			double dot = 0;
			for (int k = 0; k < 9; k++) dot += standing[i + k] * running[i + k];  // trace(S^T R), the same moment of the clip
			worst = Math.max(worst, Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, (dot - 1) / 2)))));
		}
		assertTrue(worst < 12, "running, the wand turned " + worst + " deg in the hand from how it's held standing");
	}

	/** The wand's rotation in the hand's frame (3x3, row-major, 9 per frame), 0.4 to 0.9 s after a shot. */
	private static float[] wandInHand(HeroModel m, double forward) {
		HeroAnimator anim = new HeroAnimator(m);
		int hand = m.node("hand_R"), wand = m.node("weapon");
		float[] out = new float[9 * 60];
		for (int i = 0; i < 240; i++) {
			if (i == 60) anim.shot();
			anim.update(new HeroAnimator.Input(forward, 0, 0, true, 86, 112, HeroAnimator.Wall.NONE, 0, HeroAnimator.ALT_FIRE, false), 1 / 120f);
			if (i < 110 || i >= 170) continue;  // at full strength (it fades out after a second)
			float[] w = anim.lastWorld();
			int f = (i - 110) * 9;
			for (int r = 0; r < 3; r++) for (int c = 0; c < 3; c++) {
				float v = 0;
				for (int k = 0; k < 3; k++) v += w[hand * 12 + k * 4 + r] * w[wand * 12 + k * 4 + c];  // hand^T * wand
				out[f + r * 3 + c] = v;
			}
		}
		return out;
	}

	/** A shot right after a reload starts shooting at once (it waited for the reload to fade out, then lagged). */
	@Test
	void shotAfterReloadStartsAtOnce() throws Exception {
		HeroModel m = celeste();
		HeroAnimator anim = new HeroAnimator(m);
		float dt = 1 / 120f;
		for (int i = 0; i < 120 * 2; i++) {
			float reload = i < 200 ? i / 200f : -1;
			if (i == 201) anim.shot();
			anim.update(new HeroAnimator.Input(0, 0, 0, true, 86, 112, HeroAnimator.Wall.NONE, 0, HeroAnimator.ATTACK, false, 0xF, reload), dt);
			if (i == 202) assertTrue(anim.debugLine().contains("SHOOT shoot_idle_start"), "a frame after the shot: " + anim.debugLine());
		}
	}

	/**
	 * The reload's arms don't move with the camera's pitch: the aimed arms put the hand up to 0.7 m off the
	 * wand toss's path, and the wand came back to the wrong place (worst in jumps, looking up or down).
	 */
	@Test
	void reloadIgnoresThePitch() throws Exception {
		HeroModel m = celeste();
		double[] up = reloadHand(m, -60), down = reloadHand(m, 60);
		double apart = Math.sqrt(sq(up[0] - down[0]) + sq(up[1] - down[1]) + sq(up[2] - down[2]));
		assertTrue(apart < 0.15, "mid-reload, looking up or down moved the hand " + apart + " m");
	}

	/** The hand's place relative to the hips 0.9 s into a reload in the air, looking at {@code pitch}. */
	private static double[] reloadHand(HeroModel m, float pitch) {
		HeroAnimator anim = new HeroAnimator(m);
		int hand = m.node("hand_R"), pelvis = m.node("pelvis");
		for (int i = 0; i < 168; i++) {
			float t = i / 120f;
			boolean air = t > 0.3f;
			var in = new HeroAnimator.Input(0, 0, air ? 3 : 0, !air, 86, 112, HeroAnimator.Wall.NONE, pitch, HeroAnimator.ALT_FIRE, false, 0xF, t > 0.5f ? (t - 0.5f) / 2 : -1);
			if (i == 36) anim.ability("citadel_ability_jump", in, Double.NaN);
			anim.update(in, 1 / 120f);
		}
		float[] w = anim.lastWorld();
		return new double[] {w[hand * 12 + 3] - w[pelvis * 12 + 3], w[hand * 12 + 7] - w[pelvis * 12 + 7], w[hand * 12 + 11] - w[pelvis * 12 + 11]};
	}

	/**
	 * The idle doesn't snap back to its start. Standing, Deadlock's hero drifts at about 0.7 blocks/s now
	 * and then, which switched to running for a moment and restarted the idle on the way back.
	 */
	@Test
	void idleDoesNotRestart() throws Exception {
		HeroModel m = celeste();
		HeroAnimator anim = new HeroAnimator(m);
		var still = new HeroAnimator.Input(0, 0, 0, true, 86, 112, HeroAnimator.Wall.NONE);
		var drift = new HeroAnimator.Input(0.7, 0, 0, true, 86, 112, HeroAnimator.Wall.NONE);
		for (int i = 0; i < 120 * 4; i++) {
			anim.update(i % 240 < 60 ? drift : still, 1 / 120f);
			assertTrue(anim.state() == HeroAnimator.State.IDLE, "a standing drift started " + anim.state() + " at " + i / 120f + " s");
		}
		// And standing again after a real run carries on the idle where it was, on the running clock.
		HeroAnimator reference = new HeroAnimator(m);
		var run = new HeroAnimator.Input(5, 0, 0, true, 86, 112, HeroAnimator.Wall.NONE);
		anim = new HeroAnimator(m);
		for (int i = 0; i < 120 + 30 + 120; i++) {
			reference.update(still, 1 / 120f);
			anim.update(i >= 120 && i < 150 ? run : still, 1 / 120f);
		}
		int hand = m.node("hand_L");
		float[] x = anim.lastWorld(), y = reference.lastWorld();
		double d = Math.sqrt(sq(x[hand * 12 + 3] - y[hand * 12 + 3]) + sq(x[hand * 12 + 7] - y[hand * 12 + 7]) + sq(x[hand * 12 + 11] - y[hand * 12 + 11]));
		assertTrue(d < 0.005, "a second after stopping, the hand is " + d + " m from where an uninterrupted idle has it");
	}

	/** Looking up raises the head and looking down lowers it, in and out of weapon stance. */
	@Test
	void aimFollowsThePitch() throws Exception {
		HeroModel m = celeste();
		for (long buttons : new long[] {0, HeroAnimator.ALT_FIRE}) {
			double up = eyeElevation(m, -60, buttons), level = eyeElevation(m, 0, buttons), down = eyeElevation(m, 60, buttons);
			String what = buttons == 0 ? "out of combat" : "in weapon stance";
			assertTrue(up > level + 20, what + ": looking up only raised the eyes from " + level + " to " + up + " deg");
			assertTrue(down < level - 20, what + ": looking down only lowered the eyes from " + level + " to " + down + " deg");
		}
	}

	/** How high the eyes look (degrees above level, from the head) after a second standing at {@code pitch}. */
	private static double eyeElevation(HeroModel m, float pitch, long buttons) {
		HeroAnimator anim = new HeroAnimator(m);
		var in = new HeroAnimator.Input(0, 0, 0, true, 86, 112, HeroAnimator.Wall.NONE, pitch, buttons, false);
		for (int i = 0; i < 120; i++) anim.update(in, 1 / 120f);
		float[] w = anim.lastWorld();
		int head = m.node("head"), l = m.node("eye_0_L"), r = m.node("eye_0_R");
		double x = (w[l * 12 + 3] + w[r * 12 + 3]) / 2 - w[head * 12 + 3], y = (w[l * 12 + 7] + w[r * 12 + 7]) / 2 - w[head * 12 + 7];
		double z = (w[l * 12 + 11] + w[r * 12 + 11]) / 2 - w[head * 12 + 11];
		return Math.toDegrees(Math.atan2(y, Math.hypot(x, z)));
	}

	/**
	 * Combat through the animator: shooting standing, running and crouched, reloads, melee, each of
	 * Celeste's abilities, and the orb raised, held and thrown, looking up and down throughout.
	 */
	@Test
	void combatStaysOnTheBody() throws Exception {
		HeroModel m = celeste();
		HeroAnimator anim = new HeroAnimator(m);
		float dt = 1 / 120f;
		int pelvis = m.node("pelvis");
		String[] events = {"ability_melee_unicorn", "ability_unicorn_radiantblast", "ability_unicorn_prismaticguard", "ability_unicorn_luminousstrike",
			"citadel_ability_melee_parry", "ability_melee_unicorn"};
		for (int i = 0; i < 120 * 16; i++) {
			float t = i * dt;
			double forward = t > 3 && t < 6 || t > 11 ? 6 : 0;
			boolean crouched = t > 6 && t < 8;
			long buttons = (t > 1 && t < 7 ? HeroAnimator.ATTACK : 0) | (Math.abs(t - 4) < 0.05 || Math.abs(t - 7.5) < 0.05 ? HeroAnimator.RELOAD : 0);
			boolean channeling = t > 13 && t < 15;
			var in = new HeroAnimator.Input(forward, 0, 0, true, crouched ? 55 : 86, crouched ? 64 : 112, HeroAnimator.Wall.NONE,
				(float) Math.sin(t * 2) * 80, buttons, channeling);
			if (t > 1 && t < 7 && i % 18 == 0) anim.shot();
			int e = (int) ((t - 8.5f) / 0.7f);
			if (t > 8.5f && e < events.length && Math.abs(t - 8.5f - e * 0.7f) < dt / 2) anim.ability(events[e], in, Double.NaN);
			if (Math.abs(t - 12.9f) < dt / 2) anim.ability("ability_unicorn_dazzlingorb", in, Double.NaN);
			float[] skin = anim.update(in, dt);
			double reach = reach(m, skin, m.subtree("weaponPivot"), false);
			assertTrue(reach < MAX_REACH, "at " + t + " s (" + anim.debugLine() + ") reaches " + reach + " m");
			double skirt = skirtReach(m, skin, anim.lastWorld(), pelvis);
			assertTrue(skirt < SKIRT_REACH, "at " + t + " s (" + anim.debugLine() + ") the skirt is " + skirt + " m from the hips");
		}
		assertTrue(anim.debugLine().contains("action NONE"), "the orb never finished: " + anim.debugLine());
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
