package dev.deadcraft.client.hero;

/**
 * Picks and blends the hero's clips, standing in for Deadlock's own animation graph. Continuous
 * states come from the hero's motion: idle, eight-way run (crouched or not) with the play rate
 * following the speed, fall, and slide (read from the hero's state: Deadlock lowers the collision
 * hull for both a crouch and a slide, but only a crouch lowers the eye). One-shot moves come from
 * Deadlock's movement ability events (dash, mantle, jump and air jump), which say exactly when they
 * start, and from the motion (landing, stopping). A new state fades in over {@link #FADE_S}.
 *
 * <p>Clips blend per joint ({@link HeroModel.Pose}); on top, the upper body turns towards where the
 * camera looks ({@link #setLook}), spread from the lower spine to the head, as Deadlock's heroes do
 * before their feet follow.
 */
public final class HeroAnimator {
	/** One frame of what the hero is doing. Speeds in blocks per second, relative to facing. */
	public record Input(double forward, double right, double up, boolean grounded, float eyeHeight, float hullHeight, Wall wall) {}

	/** Which side of the body a wall is on, in the air (Deadlock can bounce off it), from Minecraft's blocks. */
	public enum Wall { NONE, FORWARD, LEFT, RIGHT }

	static final float FADE_S = 0.15f;
	/** Speed at which the run clips play at normal rate, blocks/s. */
	static float runSpeed = 5f;
	/** Deadlock's dash is a short burst; the clip's tail is a recovery to standing, which we skip. */
	static final float DASH_MAX_S = 0.4f;
	/**
	 * Deadlock's mantle is quicker than the clip, whose tail is a stand-up we never want (Deadlock often
	 * hands straight into a slide). The climb plays sped up and ends early.
	 */
	static final float MANTLE_RATE = 1.4f, MANTLE_MAX_S = 0.8f;
	/** A crouch (eye at crouch height) must hold this long: the eye dips for a tick as a slide ends. */
	static final float CROUCH_ENTER_S = 0.06f;
	static final float SLIDE_ENTER_S = 0.03f;
	/** Run/idle switching with a margin, so speeds near the line don't flicker between the two. */
	static final double MOVE_START = 0.5, MOVE_STOP = 0.2;
	/** Body turn speed (degrees/s) at which standing feet fully step. */
	static final float TURN_STEP_RATE = 240f;
	/** Step cycles per second at full turning speed (1.5 looked like scurrying). */
	static final float STEP_CYCLES_PER_S = 0.7f;
	/** How strongly the stepping legs show at full turning speed, and how fast that eases in and out. */
	static final float STEP_WEIGHT = 0.4f, STEP_EASE_S = 0.15f;
	/** A fall this long lands with an impact; a run this fast stops with a skid. */
	static final float LAND_AFTER_S = 0.45f, STOP_FROM_SPEED = 3f;
	/** The upper body turns at most this far from the feet, degrees. */
	static final float LOOK_LIMIT = 75f;
	/** Shares of the upper-body turn, lower spine to head (Celeste's joint names). */
	private static final String[] LOOK_NODES = {"spine_1", "spine_2", "spine_3", "neck_0", "head"};
	private static final float[] LOOK_SHARES = {0.15f, 0.2f, 0.2f, 0.2f, 0.25f};

	private static final String[] DIRS = {"n", "ne", "e", "se", "s", "sw", "w", "nw"};

	enum State { IDLE, RUN, CROUCH_IDLE, CROUCH_RUN, JUMP, AIR_JUMP, FALL, SLIDE, DASH, MANTLE, WALL, LAND, STOP }

	private final HeroModel model;
	private final HeroModel.Pose pose, previous, shown, steps;
	/**
	 * Where additive clips (landing, skid) apply: below the pelvis (spine, arms, head, legs). Their root
	 * and pelvis channels are in another frame, and the skirt's cloth isn't in them at all.
	 */
	private final boolean[] layered;
	/** Eased strength of the stepping legs, so steps fade in and out instead of switching. */
	private float stepWeight;
	/** The legs, for stepping round on the spot without moving the hips. */
	private final boolean[] legs;
	private final float[] world, skin;
	private final int[] lookNodes;
	private final float[] lookShares;
	/** The twist, the hair swing and the weapon in the hand, applied when building the skeleton. */
	private final HeroModel.Rig rig = new HeroModel.Rig();
	/** The arm's share of the upper-body twist (the shares of the twisted joints above the hand). */
	private float armShare;
	/**
	 * Hair the clips leave hanging (Deadlock moves it with physics at runtime): chain roots and their
	 * share of the swing. The ponytail bends along its length.
	 */
	private static final String[] HAIR_NODES = {"ponytail_0", "ponytail_5", "ponytail_10", "hair_ribbon_0_L", "hair_ribbon_0_R", "hair_tress_0_R"};
	private static final float[] HAIR_SHARES = {0.45f, 0.3f, 0.25f, 0.7f, 0.7f, 0.5f};
	/** Hair swing at run speed, most it swings back, most forward, and how fast it follows, degrees and seconds. */
	static final float HAIR_AT_RUN = 40f, HAIR_MAX = 60f, HAIR_MIN = -10f, HAIR_EASE_S = 0.18f;
	private float hairSwing;
	private State state = State.IDLE;
	/** A move started by an ability event or the motion, held until it ends. */
	private State move;
	private float stateTime, fade = 1, airTime, fadeTime = FADE_S;
	private float runPhase;  // 0..1, shared by every direction so blends stay in step
	private float standingEye, eye, clock, turnRate, turnPhase, standingHull, hull, look, recentSpeed;
	/** How long the hero's state has said "sliding" (hull down, eye up); a tick's flicker isn't a slide. */
	private float slideStateTime, crouchStateTime;
	private Wall wallSide = Wall.NONE;
	private double speed;
	private boolean grounded;
	private String dashClip = "dash_ground", mantleClip = "mantle_64", clipA = "", clipB = "";
	private float blend;

	public HeroAnimator(HeroModel model) {
		this.model = model;
		pose = model.newPose();
		previous = model.newPose();
		shown = model.newPose();
		steps = model.newPose();
		legs = model.subtree("leg_upper_L", "leg_upper_R");
		layered = model.subtree("spine_0", "leg_upper_L", "leg_upper_R");
		world = new float[model.nodeCount() * 12];
		skin = new float[model.skinnedJointCount() * 12];
		int found = 0;
		int[] nodes = new int[LOOK_NODES.length];
		float[] shares = new float[LOOK_NODES.length];
		for (int i = 0; i < LOOK_NODES.length; i++) {
			int n = model.node(LOOK_NODES[i]);
			if (n < 0) continue;
			nodes[found] = n;
			shares[found++] = LOOK_SHARES[i];
		}
		lookNodes = java.util.Arrays.copyOf(nodes, found);
		lookShares = java.util.Arrays.copyOf(shares, found);
		rig.twistNodes = lookNodes;
		rig.twistShares = lookShares;
		rig.follower = model.node("weaponPivot");
		rig.grip = model.node("weaponHand_R");
		rig.leader = model.node("hand_R");
		if (rig.leader >= 0) {
			for (int i = 0; i < lookNodes.length; i++) if (model.isAncestor(lookNodes[i], rig.leader)) armShare += lookShares[i];
		}
		int hairFound = 0;
		int[] hair = new int[HAIR_NODES.length];
		float[] hairShares = new float[HAIR_NODES.length];
		for (int i = 0; i < HAIR_NODES.length; i++) {
			int n = model.node(HAIR_NODES[i]);
			if (n < 0) continue;
			hair[hairFound] = n;
			hairShares[hairFound++] = HAIR_SHARES[i];
		}
		rig.swingNodes = java.util.Arrays.copyOf(hair, hairFound);
		rig.swingShares = java.util.Arrays.copyOf(hairShares, hairFound);
	}

	/** The node world matrices of the last {@link #update} (for tests). */
	float[] lastWorld() {
		return world;
	}

	public State state() {
		return state;
	}

	public boolean sliding() {
		return state == State.SLIDE;
	}

	/** How fast the drawn body is turning, degrees per second (positive: to its right). */
	public void setTurnRate(float degreesPerSecond) {
		turnRate = degreesPerSecond;
	}

	/** Where the camera looks relative to the drawn body, degrees (Minecraft yaw: positive to the right). */
	public void setLook(float degrees) {
		look = Math.max(-LOOK_LIMIT, Math.min(LOOK_LIMIT, degrees));
	}

	/**
	 * A Deadlock ability was used (from the plugin's event ring). Movement abilities start moves.
	 * {@code ledgeBlocks}: for a mantle, how high the ledge in front is above the feet (NaN if unknown).
	 */
	public void ability(String name, Input in, double ledgeBlocks) {
		String n = name.toLowerCase();
		if (n.contains("dash")) {
			dashClip = in.grounded() ? "dash_ground" : "dash_air_" + airDirection(in);
			start(State.DASH);
		} else if (n.contains("mantle")) {
			// Celeste has climbs for 32, 64, 96 and 128 units: half a block to two blocks.
			int units = Double.isNaN(ledgeBlocks) ? 64 : (int) Math.max(32, Math.min(128, Math.round(ledgeBlocks * 2) * 32));
			mantleClip = "mantle_" + units;
			start(State.MANTLE);
		} else if (n.contains("jump")) {
			start(in.grounded() || airTime < 0.25f ? State.JUMP : State.AIR_JUMP);
		}
	}

	private void start(State s) {
		move = s;
		enter(s);
	}

	private void enter(State next) {
		HeroRenderer.LOG.info("Deadcraft: anim {} -> {} after {} s (speed {} b/s, {}, eye {}, hull {}){}", state, next,
			String.format("%.2f", stateTime), String.format("%.1f", speed), grounded ? "ground" : "air", String.format("%.0f", eye),
			String.format("%.0f", hull), next == State.MANTLE ? " " + mantleClip : "");
		previous.copyFrom(shown);
		state = next;
		stateTime = 0;
		fade = 0;
		// Bracing against a wall eases in more slowly: snapping the feet round looked abrupt.
		fadeTime = next == State.WALL ? 0.3f : FADE_S;
	}

	/** Advances by {@code dt} seconds and returns the skinning matrices (skinned joints * 12). */
	public float[] update(Input in, float dt) {
		standingEye = Math.max(standingEye, in.eyeHeight());
		eye = in.eyeHeight();
		hull = in.hullHeight();
		standingHull = Math.max(standingHull, hull);
		clock += dt;
		turnPhase = (turnPhase + dt * Math.min(1f, Math.abs(turnRate) / TURN_STEP_RATE) * STEP_CYCLES_PER_S) % 1f;
		float stepTarget = Math.min(1f, Math.abs(turnRate) / TURN_STEP_RATE);
		stepWeight += (stepTarget - stepWeight) * (1 - (float) Math.exp(-dt / STEP_EASE_S));
		boolean landed = in.grounded() && !grounded;
		float airTimeBefore = airTime;
		grounded = in.grounded();
		airTime = in.grounded() ? 0 : airTime + dt;
		speed = Math.hypot(in.forward(), in.right());
		recentSpeed = (float) Math.max(speed, recentSpeed * Math.exp(-dt / 0.2));
		boolean slideState = standingHull > 0 && hull < standingHull * 0.75f && eye >= standingEye * 0.9f;
		slideStateTime = slideState ? slideStateTime + dt : 0;
		boolean crouchState = standingEye > 0 && eye < standingEye * 0.8f;
		crouchStateTime = crouchState ? crouchStateTime + dt : 0;
		boolean wasCrouched = state == State.CROUCH_IDLE || state == State.CROUCH_RUN;
		boolean crouched = crouchStateTime > 0 && (wasCrouched || crouchStateTime >= CROUCH_ENTER_S);
		if (move != null && !moveContinues(in, speed)) move = null;
		if (move == null && landed && airTimeBefore > LAND_AFTER_S && speed < 2 && !slideState && !crouched) {
			start(State.LAND);
		}
		Wall wallBefore = wallSide;
		State next = move != null ? move : choose(in, speed, crouched);
		if (move == null && state == State.RUN && next == State.IDLE && recentSpeed > STOP_FROM_SPEED) {
			start(State.STOP);
			next = State.STOP;
		}
		if (next != state || next == State.WALL && wallSide != wallBefore) enter(next);
		stateTime += dt;
		fade = Math.min(1, fade + dt / fadeTime);
		if (state == State.RUN || state == State.CROUCH_RUN) {
			float rate = (float) Math.max(0.5, Math.min(2.0, speed / (state == State.RUN ? runSpeed : runSpeed * 0.5)));
			HeroModel.Clip c = model.clips.get(state == State.RUN ? "out_of_combat_run_n" : "out_of_combat_crouch_run_n");
			if (c != null) runPhase = (runPhase + dt * rate / c.duration()) % 1f;
		}

		pose.clear();
		sample(in);
		pose.finish();
		shown.copyFrom(previous);
		shown.blendTowards(pose, fade < 1 ? smooth(fade) : 1);
		// Model space turns the other way round from Minecraft's yaw (the renderer turns by -yaw).
		rig.twist = (float) -Math.toRadians(look);
		rig.followerTwist = rig.twist * armShare;
		// Hair streams back with forward speed and lifts in a fall.
		float hairTarget = (float) Math.max(HAIR_MIN, Math.min(HAIR_MAX, in.forward() / runSpeed * HAIR_AT_RUN
			+ (in.grounded() ? 0 : Math.max(0, Math.min(25, -in.up() * 4)))));
		hairSwing += (hairTarget - hairSwing) * (1 - (float) Math.exp(-dt / HAIR_EASE_S));
		rig.swing = (float) Math.toRadians(hairSwing);
		model.skin(shown, world, rig, skin);
		return skin;
	}

	private boolean moveContinues(Input in, double speed) {
		return switch (move) {
			case DASH -> stateTime < Math.min(DASH_MAX_S, clipDuration(dashClip));
			// Through the climb (Deadlock lowers the hull while climbing, so that says nothing yet); over
			// on landing, into a slide or onto the ledge.
			case MANTLE -> stateTime < MANTLE_MAX_S && !(in.grounded() && stateTime > 0.15f);
			// A wall beside the hero takes over from a jump (Deadlock can bounce off it).
			case JUMP -> stateTime < clipDuration("jump_ground") && !(in.grounded() && stateTime > 0.2f)
				&& !(in.wall() != Wall.NONE && airTime > 0.15f);
			case AIR_JUMP -> stateTime < clipDuration("jump_air") && !in.grounded() && !(in.wall() != Wall.NONE && airTime > 0.15f);
			// Short: an impact or a skid, cut the moment the hero moves off again.
			case LAND -> stateTime < Math.min(0.4f, clipDuration("landing_impact_idle")) && in.grounded() && speed < 1.5;
			case STOP -> stateTime < Math.min(0.45f, clipDuration("run_to_stop_stand")) && in.grounded() && speed < MOVE_START;
			default -> false;
		};
	}

	private State choose(Input in, double speed, boolean crouched) {
		if (!in.grounded()) {
			if (in.wall() != Wall.NONE && airTime > 0.15f) {
				if (state != State.WALL || wallSide != in.wall()) wallSide = in.wall();
				return State.WALL;
			}
			// Walked off an edge (no jump event): fall after a moment, so steps down don't flicker.
			return airTime > 0.15f || state == State.FALL ? State.FALL : state;
		}
		if (slideStateTime >= SLIDE_ENTER_S || state == State.SLIDE && slideStateTime > 0) return State.SLIDE;
		// Climbing a step barely moves sideways: going up counts as moving too.
		double motion = Math.max(speed, Math.abs(in.up()));
		boolean wasMoving = state == State.RUN || state == State.CROUCH_RUN;
		boolean moving = wasMoving ? motion > MOVE_STOP || stateTime < 0.15f : motion > MOVE_START;
		if (!wasMoving && (state == State.IDLE || state == State.CROUCH_IDLE) && stateTime < 0.1f) moving = false;
		if (crouched) return moving ? State.CROUCH_RUN : State.CROUCH_IDLE;
		return moving ? State.RUN : State.IDLE;
	}

	private void sample(Input in) {
		switch (state) {
			case IDLE -> idle("out_of_combat_stand_idle", "out_of_combat_run_");
			case CROUCH_IDLE -> idle("out_of_combat_crouch_idle", "out_of_combat_crouch_run_");
			case RUN -> directional("out_of_combat_run_", in);
			case CROUCH_RUN -> directional("out_of_combat_crouch_run_", in);
			case JUMP -> play("jump_ground", stateTime);
			case AIR_JUMP -> play("jump_air", stateTime);
			case FALL -> play("in_air_loop_down", stateTime);
			case MANTLE -> play(mantleClip, stateTime * MANTLE_RATE);
			case WALL -> play("wall_attach_" + wallSide.name().toLowerCase(), stateTime * 0.7f);
			case DASH -> play(dashClip, stateTime);
			case LAND -> layer("out_of_combat_stand_idle", "landing_impact_idle");
			case STOP -> layer("out_of_combat_stand_idle", "run_to_stop_stand");
			case SLIDE -> {
				float start = clipDuration("slide_start");
				if (stateTime < start) play("slide_start", stateTime);
				else play("slide_loop", stateTime - start);
			}
		}
	}

	/** A base clip with an additive clip layered on it (fading out over its last tenth of a second). */
	private void layer(String baseClip, String layerClip) {
		HeroModel.Clip base = model.clips.get(baseClip), top = model.clips.get(layerClip);
		if (base != null) pose.add(base, stateTime, 1);
		pose.finish();
		if (top == null || !top.additive()) return;
		float left = top.duration() - stateTime;
		pose.addLayer(top, stateTime, Math.max(0, Math.min(1, left / 0.1f)), layered);
	}

	/**
	 * Standing still: the idle, with sideways steps while the body turns on the spot (Celeste has no
	 * standing turn clips; the strafe run at a slow phase reads as stepping round). The steps go into
	 * the legs only: blended into the whole body they lowered the hips, a visible dip.
	 */
	private void idle(String idleClip, String runPrefix) {
		HeroModel.Clip c = model.clips.get(idleClip);
		if (c != null) pose.add(c, stateTime, 1);
		float w = stepWeight * STEP_WEIGHT;
		HeroModel.Clip step = model.clips.get(runPrefix + (turnRate > 0 ? "e" : "w"));
		if (w <= 0 || step == null) return;
		pose.finish();
		steps.clear();
		steps.add(step, turnPhase * step.duration(), 1);
		steps.finish();
		pose.blendTowards(steps, w, legs);
	}

	/** The two run clips around the movement direction, blended by angle, at the shared phase. */
	private void directional(String prefix, Input in) {
		double angle = Math.toDegrees(Math.atan2(in.right(), in.forward()));  // 0 forward, 90 right
		double sector = ((angle % 360) + 360) % 360 / 45.0;
		int i0 = (int) Math.floor(sector) % 8, i1 = (i0 + 1) % 8;
		float w1 = (float) (sector - Math.floor(sector));
		clipA = prefix + DIRS[i0];
		clipB = prefix + DIRS[i1];
		blend = w1;
		playPhase(clipA, runPhase, 1 - w1);
		playPhase(clipB, runPhase, w1);
	}

	private static String airDirection(Input in) {
		double angle = Math.toDegrees(Math.atan2(in.right(), in.forward()));
		if (Math.abs(angle) <= 45) return "forward";
		if (Math.abs(angle) >= 135) return "back";
		return angle > 0 ? "right" : "left";
	}

	private void play(String clip, float time) {
		HeroModel.Clip c = model.clips.get(clip);
		if (c == null) c = model.clips.get("out_of_combat_stand_idle");
		if (c != null) pose.add(c, time, 1);
	}

	private void playPhase(String clip, float phase, float weight) {
		HeroModel.Clip c = model.clips.get(clip);
		if (c != null) pose.add(c, phase * c.duration(), weight);
	}

	private float clipDuration(String clip) {
		HeroModel.Clip c = model.clips.get(clip);
		return c == null ? 0 : c.duration();
	}

	private static float smooth(float x) {
		return x * x * (3 - 2 * x);
	}

	/** Everything about the current blend, for the spike report. */
	String debugLine() {
		return String.format("state %s for %.2f s (fade %.2f of %.2f s), move %s, clips %s/%s %.2f, dash %s, mantle %s, wall %s, look %.0f, turn %.0f, "
			+ "speed %.1f, %s, eye %.0f, hull %.0f, air %.2f s", state, stateTime, fade, fadeTime, move, clipA, clipB, blend, dashClip, mantleClip,
			wallSide, look, turnRate, speed, grounded ? "ground" : "air", eye, hull, airTime);
	}

	/** For the test HUD. */
	public String hudLine() {
		return String.format("anim: %s%s  speed %.1f b/s  %s  eye %.0f/%.0f  hull %.0f/%.0f  look %.0f", state,
			state == State.DASH ? " " + dashClip : "", speed, grounded ? "ground" : "air", eye, standingEye, hull, standingHull, look);
	}

	public String describe() {
		return state + (state == State.RUN || state == State.CROUCH_RUN ? String.format(" %s/%s %.2f", clipA, clipB, blend) : "")
			+ (state == State.DASH ? " " + dashClip : "") + String.format(" standing eye %.0f", standingEye);
	}
}
