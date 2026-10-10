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
	/**
	 * One frame of what the hero is doing. Speeds in blocks per second, relative to facing; {@code pitch}
	 * where the camera looks, degrees, positive down; {@code buttons} what the player holds (Deadlock's
	 * InputButton bits); {@code channeling} whether a signature ability is channelling; {@code abilitiesReady}
	 * bit n: signature ability n+1 would cast if pressed; {@code reload} how far through the weapon's reload
	 * (0 to 1, NaN if reloading but unknown how far; negative when not reloading).
	 */
	public record Input(double forward, double right, double up, boolean grounded, float eyeHeight, float hullHeight, Wall wall,
		float pitch, long buttons, boolean channeling, int abilitiesReady, float reload) {
		public Input(double forward, double right, double up, boolean grounded, float eyeHeight, float hullHeight, Wall wall) {
			this(forward, right, up, grounded, eyeHeight, hullHeight, wall, 0, 0, false, 0xF, -1);
		}

		public Input(double forward, double right, double up, boolean grounded, float eyeHeight, float hullHeight, Wall wall,
			float pitch, long buttons, boolean channeling) {
			this(forward, right, up, grounded, eyeHeight, hullHeight, wall, pitch, buttons, channeling, 0xF, -1);
		}

		public Input(double forward, double right, double up, boolean grounded, float eyeHeight, float hullHeight, Wall wall,
			float pitch, long buttons, boolean channeling, int abilitiesReady) {
			this(forward, right, up, grounded, eyeHeight, hullHeight, wall, pitch, buttons, channeling, abilitiesReady, -1);
		}

		boolean reloading() {
			return !(reload < 0);
		}
	}

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
	/** Back up this long before standing: spamming crouch raised the eye for a few hundredths of a second at a time. */
	static final float CROUCH_EXIT_S = 0.08f;
	/**
	 * Run/idle switching with a margin, so speeds near the line don't flicker between the two. Standing,
	 * Deadlock's hero drifts at up to about 0.7 blocks/s now and then (seen in game): not a run.
	 */
	static final double MOVE_START = 1.0, MOVE_STOP = 0.5;
	/** Crouched, a crawl (spamming crouch: ~0.4 blocks/s) is still moving. */
	static final double CROUCH_MOVE_START = 0.3, CROUCH_MOVE_STOP = 0.15;
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

	/** Deadlock's InputButton bits for fire, alt fire (zoom), reload and melee (Weapon1, found in game). */
	static final long ATTACK = 0x1, ALT_FIRE = 0x800, RELOAD = 0x2000, MELEE = 0x100000000L;
	/** Melee held this long is a heavy melee (the wind-up holds until the hit); shorter, a quick one (by eye: unverified). */
	static final float HEAVY_AFTER_S = 0.25f;
	/** The shooting clips play at this rate (at full speed they looked too quick). */
	static final float SHOOT_RATE = 0.8f;
	/** Each shot restarts the recoil with this short a crossfade. */
	static final float RECOIL_FADE_S = 0.05f;
	/** A heavy wind-up held this long with no hit gives up. */
	static final float HEAVY_MAX_WAIT_S = 1.2f;
	/** The heavy hit plays all of it (its tail settles the feet); after the strike, at double speed. */
	static final float HEAVY_HIT_S = 9f, HEAVY_STRIKE_S = 0.3f;
	/** Weapon stance lasts this long after the last shot or action (a guess: unverified against Deadlock). */
	static final float COMBAT_HOLD_S = 3f;
	/** Stance changes, aim strength and actions ease over these. */
	static final float STANCE_S = 0.25f, AIM_EASE_S = 0.12f, ACTION_IN_S = 0.1f, ACTION_OUT_S = 0.2f;
	/** Shots this close together are continuous fire (the loop); the aimed pose holds this long after the last. */
	static final float FIRE_GAP_S = 0.3f, SHOOT_LINGER_S = 1.0f;
	/** A cast started on its button with no event (or orb channel) by this long after was refused (on cooldown). */
	static final float CONFIRM_S = 1.3f;
	static final String ORB_START = "ability_unicorn_dazzlingorb_start";
	/**
	 * Celeste's signature abilities by slot (Deadlock's scripts/heroes.vdata) and the clip each plays. Casts
	 * start on the button press: the ability event only comes after the cast delay (0.3 s for Radiant
	 * Blast, about a second for the orb). Luminous Strike aims first and casts on a later click, so it
	 * waits for its event (null).
	 */
	private static final String[] SLOT_NAMES = {"radiantblast", "prismaticguard", "luminousstrike", "dazzlingorb"};
	private static final String[] SLOT_CLIPS = {"ability_unicorn_radiant_blast", "ability_unicorn_prismaticguard", null, ORB_START};

	/** Deadlock's InputButton bit for signature ability {@code slot} (0 to 3). */
	static long abilityButton(int slot) {
		return 0x200000000L << slot;
	}
	/** Camera pitch, degrees, at which the aim clips' up and down poses apply fully (their head turns ~60 deg). */
	static final float AIM_RANGE = 70f;

	/** An action layered over the movement: on the upper body, or the whole body when standing still. Higher kinds interrupt lower. */
	enum Action { NONE, SHOOT, RELOAD, MELEE, CAST, ORB }

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
	private final int pelvis;
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
	/** The movement's pose with the action and aim on top: what is skinned (see {@link #update}). */
	private final HeroModel.Pose top, actionPose, actionPrevious, actionMix;
	private final boolean[] upper, lower, aimed;
	private Action action = Action.NONE;
	private String actionClip = "";
	/** Time in the action, its strength, how much of the legs it takes, and its crossfade from the action before. */
	private float actionTime, clipTime, actionWeight, actionLegs, actionFade = 1;
	/** The action's own first pose: moving, only its change from this is layered over the movement. */
	private final HeroModel.Pose actionReference;
	private String referenceClip = "";
	/** A cast started from its button, waiting for the event (or channel) that says it happened. */
	private int pendingSlot = -1;
	private float pendingTime;
	private long buttonsBefore;
	private boolean shotEventsSeen, orbChanneled, actionCancelled, reloadSignalSeen, heavy, shotPending, meleeThisPress;
	/** How long melee has been held (negative: not held); a held heavy melee's repeat interval and time since its last hit. */
	private float meleeHeld = -1, heavyInterval = 2f, sinceHeavyHit = 99;
	/** How long Deadlock's heavy wind-up lasts (learned: from the wind-up's start to the hit's event), and the clip's rate. */
	private float windupS = 0.5f, actionRate = 1, pressLength;
	/** How long the eye has been back up after a crouch: spamming crouch flickers it every tenth of a second. */
	private float uncrouchedTime = 99;
	/** How much of the legs come from the action's clip (less for a melee on the move: the run's legs step). */
	private float clipLegs = 1;
	/** How far the hero moved during the action, and its top speed (logged for melee: is Deadlock's lunge longer than the clip's step?). */
	private float actionTravel, actionTopSpeed;
	private final boolean[] notLegs;
	/** Moving faster than this, blocks/s, a melee keeps the running legs. */
	static final double MELEE_STEP_SPEED = 2;
	/** The crossfade time of the current clip change. */
	private float actionFadeS = ACTION_IN_S;
	/** The spine's joints (layered over movement as a change) and the arms and wand (played as they are). */
	private final boolean[] spineChain, limbs, aimHead;
	private final int chest;
	private final float[] chestNow = new float[12], chestClip = new float[12];
	private float combatTime = COMBAT_HOLD_S, stance, aimWeight, orbWeight, sinceShot = 99, pitch;
	private int meleeCount;
	private boolean actionDone;
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
		top = model.newPose();
		actionPose = model.newPose();
		actionReference = model.newPose();
		actionPrevious = model.newPose();
		actionMix = model.newPose();
		// The weapon (hanging off the root) goes with the upper body: the reload tosses it.
		upper = model.subtree("spine_0", "weaponPivot");
		lower = new boolean[upper.length];
		for (int n = 0; n < upper.length; n++) lower[n] = !upper[n];
		// Aim clips also turn the weapon, but not weaponPivot: theirs is in the clips' other root frame.
		aimed = model.subtree("spine_0", "weapon");
		spineChain = new boolean[upper.length];
		for (String n : new String[] {"spine_0", "spine_1", "spine_2", "spine_3", "neck_0", "head"}) if (model.node(n) >= 0) spineChain[model.node(n)] = true;
		limbs = model.subtree("clavicle_L", "clavicle_R", "weaponPivot");
		chest = model.node("spine_3");
		aimHead = new boolean[upper.length];
		for (String n : new String[] {"neck_0", "head"}) if (model.node(n) >= 0) aimHead[model.node(n)] = true;
		legs = model.subtree("leg_upper_L", "leg_upper_R");
		notLegs = new boolean[legs.length];
		for (int n = 0; n < legs.length; n++) notLegs[n] = !legs[n];
		pelvis = model.node("pelvis");
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

	/** In weapon stance (shooting lately) or holding the orb: the body faces the camera more readily. */
	public boolean inCombat() {
		return combatTime < COMBAT_HOLD_S || action == Action.ORB;
	}

	/** The server fired one of the hero's shots. */
	public void shot() {
		shotEventsSeen = true;
		shotPending = true;
		sinceShot = 0;
		combatTime = 0;
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
		} else if (n.contains("parry")) {
			startAction(Action.MELEE, in.grounded() ? "parry" : "parry_inair");
		} else if (n.contains("melee")) {
			// Deadlock melees at most every ~0.8 s however fast the button is tapped, and says so with this
			// event: a quick melee's comes as it starts, a heavy one's at the hit (after the wind-up the press
			// started), and again each cooldown while the button stays down. Only events start strikes.
			boolean windingUp = action == Action.MELEE && heavy && !actionDone;
			if (meleeHeld >= HEAVY_AFTER_S || windingUp && meleeHeld < 0 && pressLength >= HEAVY_AFTER_S) {
				if (sinceHeavyHit < 4) heavyInterval = sinceHeavyHit;
				if (windingUp) windupS = Math.max(0.2f, Math.min(1f, actionTime));
				sinceHeavyHit = 0;
				if (windingUp) switchClip(in.grounded() ? "melee_hit" : "melee_in_air_hit", 0);
				else startAction(Action.MELEE, in.grounded() ? "melee_hit" : "melee_in_air_hit");
				heavy = false;
				actionRate = 1;
		actionTravel = actionTopSpeed = 0;
			} else {
				quickMelee(in);
			}
		} else if (n.startsWith("ability_")) {
			cast(n);
		}
	}

	private void quickMelee(Input in) {
		meleeCount++;
		startAction(Action.MELEE, (in.grounded() ? "melee_quick_" : "melee_quick_in_air_") + (meleeCount % 2 == 0 ? 2 : 1));
	}

	/** A signature ability's event: confirms a cast started from its button, or starts it now. */
	private void cast(String name) {
		int slot = -1;
		for (int i = 0; i < SLOT_NAMES.length; i++) if (name.contains(SLOT_NAMES[i])) slot = i;
		if (slot >= 0 && slot == pendingSlot) {
			pendingSlot = -1;
			return;
		}
		if (slot == 3) startOrb();
		else startAction(Action.CAST, slot < 0 ? "cast_start" : slot == 2 ? "throw" : SLOT_CLIPS[slot]);
	}

	private void startAction(Action kind, String clip) {
		// Higher kinds interrupt lower; nothing interrupts the orb but its own end.
		if (action == Action.ORB && kind != Action.ORB && !actionDone) return;
		if (kind.ordinal() < action.ordinal() && !actionDone) return;
		HeroRenderer.LOG.info("Deadcraft: action {} -> {} {}", action, kind, clip);
		switchClip(clip, 0, ACTION_IN_S);
		action = kind;
		referenceClip = clip;
		actionTime = 0;
		actionDone = actionCancelled = false;
		orbChanneled = heavy = false;
		actionRate = 1;
		pendingSlot = -1;
		combatTime = 0;
	}

	/** Plays {@code clip} from {@code time}, crossfading from what the action showed. */
	private void switchClip(String clip, float time) {
		switchClip(clip, time, ACTION_IN_S);
	}

	private void switchClip(String clip, float time, float fadeS) {
		if (actionWeight > 0) {
			actionPrevious.copyFrom(actionMix);
			actionFade = 0;
			actionFadeS = fadeS;
		}
		actionClip = clip;
		clipTime = time;
	}

	/** Celeste's ultimate: raising the orb, holding it while the ability channels, then throwing it. */
	private void startOrb() {
		if (action == Action.ORB && !actionDone) return;
		startAction(Action.ORB, ORB_START);
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
		uncrouchedTime = crouchState ? 0 : uncrouchedTime + dt;
		// Crouched once, stay crouched through the eye's brief rises when crouch is spammed.
		boolean crouched = crouchStateTime > 0 && (wasCrouched || crouchStateTime >= CROUCH_ENTER_S)
			|| wasCrouched && uncrouchedTime < CROUCH_EXIT_S && !slideState;
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
			// Following the speed down to a crawl (spamming crouch slows the hero to ~0.4 blocks/s; at half
			// rate the legs looked far too fast).
			// Down to a crawl when crouched (spamming crouch slows the hero to ~0.4 blocks/s); standing, no slower
			// than half rate (the last moments of a stop played in slow motion).
			float rate = (float) Math.max(state == State.RUN ? 0.5 : 0.1, Math.min(2.0, speed / (state == State.RUN ? runSpeed : runSpeed * 0.5)));
			// Under a melee's spinning hips, double-speed legs flailed: at most normal pace.
			if (action == Action.MELEE && clipLegs < 1) rate = Math.min(rate, 1f);
			HeroModel.Clip c = model.clips.get(state == State.RUN ? "out_of_combat_run_n" : "out_of_combat_crouch_run_n");
			if (c != null) runPhase = (runPhase + dt * rate / c.duration()) % 1f;
		}

		pose.clear();
		sample(in);
		pose.finish();
		shown.copyFrom(previous);
		shown.blendTowards(pose, fade < 1 ? smooth(fade) : 1);

		// Over the movement: the action (shooting, an ability...), then the aim from the camera's pitch.
		top.copyFrom(shown);
		updateAction(in, crouched, dt);
		applyAction();
		applyAim(in, dt);
		// Model space turns the other way round from Minecraft's yaw (the renderer turns by -yaw).
		rig.twist = (float) -Math.toRadians(look);
		rig.followerTwist = rig.twist * armShare;
		// Hair streams back with forward speed and lifts in a fall.
		float hairTarget = (float) Math.max(HAIR_MIN, Math.min(HAIR_MAX, in.forward() / runSpeed * HAIR_AT_RUN
			+ (in.grounded() ? 0 : Math.max(0, Math.min(25, -in.up() * 4)))));
		hairSwing += (hairTarget - hairSwing) * (1 - (float) Math.exp(-dt / HAIR_EASE_S));
		rig.swing = (float) Math.toRadians(hairSwing);
		model.skin(top, world, rig, skin);
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
		boolean moving = wasMoving ? motion > (crouched ? CROUCH_MOVE_STOP : MOVE_STOP) || stateTime < 0.15f : motion > (crouched ? CROUCH_MOVE_START : MOVE_START);
		if (!wasMoving && (state == State.IDLE || state == State.CROUCH_IDLE) && stateTime < 0.1f) moving = false;
		if (crouched) return moving ? State.CROUCH_RUN : State.CROUCH_IDLE;
		return moving ? State.RUN : State.IDLE;
	}

	private void sample(Input in) {
		switch (state) {
			case IDLE -> idle(false);
			case CROUCH_IDLE -> idle(true);
			case RUN -> directional(false, in);
			case CROUCH_RUN -> directional(true, in);
			case JUMP -> play("jump_ground", stateTime);
			case AIR_JUMP -> play("jump_air", stateTime);
			case FALL -> play("in_air_loop_down", stateTime);
			case MANTLE -> play(mantleClip, stateTime * MANTLE_RATE);
			case WALL -> play("wall_attach_" + wallSide.name().toLowerCase(), stateTime * 0.7f);
			case DASH -> play(dashClip, stateTime);
			case LAND -> layer("landing_impact_idle");
			case STOP -> layer("run_to_stop_stand");
			case SLIDE -> {
				float start = clipDuration("slide_start");
				if (stateTime < start) play("slide_start", stateTime);
				else play("slide_loop", stateTime - start);
			}
		}
	}

	/** The standing idle with an additive clip layered on it (fading out over its last tenth of a second). */
	private void layer(String layerClip) {
		stanceMix("out_of_combat_stand_idle", "weapon_stand_idle", clock, 1);
		pose.finish();
		HeroModel.Clip layer = model.clips.get(layerClip);
		if (layer == null || !layer.additive()) return;
		float left = layer.duration() - stateTime, weight = Math.max(0, Math.min(1, left / 0.1f));
		pose.addLayer(layer, stateTime, weight, layered);
		// The hips' drop: the layer's pelvis channel is in the export's Z-up root frame (not layered above),
		// where its height is the z translation; the change from the clip's first frame is the drop.
		if (pelvis < 0) return;
		float drop = model.translation(layer, pelvis, 2, stateTime) - model.translation(layer, pelvis, 2, 0);
		pose.translate(pelvis, 0, drop * weight, 0);
	}

	/** {@code weight} of the out-of-combat clip and the weapon-stance one, mixed by the stance. */
	private void stanceMix(String outOfCombat, String weapon, float time, float weight) {
		HeroModel.Clip a = model.clips.get(outOfCombat), b = model.clips.get(weapon);
		if (b == null) {
			if (a != null) pose.add(a, time, weight);
			return;
		}
		if (a != null) pose.add(a, time, weight * (1 - stance));
		pose.add(b, time, weight * stance);
	}

	/**
	 * Standing still: the idle, with sideways steps while the body turns on the spot (Celeste has no
	 * standing turn clips; the strafe run at a slow phase reads as stepping round). The steps go into
	 * the legs only: blended into the whole body they lowered the hips, a visible dip.
	 */
	private void idle(boolean crouch) {
		// On the running clock, not the state's: every return to standing restarted the idle (a visible snap,
		// often, as standing and running flicker at the edges of a stop).
		stanceMix(crouch ? "out_of_combat_crouch_idle" : "out_of_combat_stand_idle", crouch ? "weapon_crouch_idle" : "weapon_stand_idle", clock, 1);
		float w = stepWeight * STEP_WEIGHT;
		String runPrefix = (stance > 0.5f ? "weapon_" : "out_of_combat_") + (crouch ? "crouch_run_" : "run_");
		HeroModel.Clip step = model.clips.get(runPrefix + (turnRate > 0 ? "e" : "w"));
		if (w <= 0 || step == null) return;
		pose.finish();
		steps.clear();
		steps.add(step, turnPhase * step.duration(), 1);
		steps.finish();
		pose.blendTowards(steps, w, legs);
	}

	/** The two run clips around the movement direction, blended by angle, at the shared phase, in both stances. */
	private void directional(boolean crouch, Input in) {
		String run = crouch ? "crouch_run_" : "run_";
		if (stance < 1) directional("out_of_combat_" + run, in, 1 - stance);
		if (stance > 0) directional("weapon_" + run, in, stance);
	}

	private void directional(String prefix, Input in, float weight) {
		double angle = Math.toDegrees(Math.atan2(in.right(), in.forward()));  // 0 forward, 90 right
		double sector = ((angle % 360) + 360) % 360 / 45.0;
		int i0 = (int) Math.floor(sector) % 8, i1 = (i0 + 1) % 8;
		float w1 = (float) (sector - Math.floor(sector));
		clipA = prefix + DIRS[i0];
		clipB = prefix + DIRS[i1];
		blend = w1;
		playPhase(clipA, runPhase, (1 - w1) * weight);
		playPhase(clipB, runPhase, w1 * weight);
	}

	/** Starts, advances and ends the action from the buttons, shots and channel; eases the stance. */
	private void updateAction(Input in, boolean crouched, float dt) {
		long b = in.buttons(), pressed = b & ~buttonsBefore;
		buttonsBefore = b;
		boolean attack = (b & ATTACK) != 0;
		sinceShot += dt;
		// The server's shot events when they come (they do on this setup); the fire button otherwise.
		boolean firing = sinceShot < FIRE_GAP_S || attack && !shotEventsSeen;
		boolean shooting = firing || attack || sinceShot < SHOOT_LINGER_S;
		if (firing || (b & ALT_FIRE) != 0 || action != Action.NONE && !actionDone) combatTime = 0;
		else combatTime += dt;
		stance = approach(stance, combatTime < COMBAT_HOLD_S ? 1 : 0, dt / STANCE_S);

		for (int slot = 0; slot < SLOT_CLIPS.length; slot++) {
			if ((pressed & abilityButton(slot)) == 0 || SLOT_CLIPS[slot] == null || (in.abilitiesReady() & 1 << slot) == 0) continue;
			if (slot == 3) startOrb();
			else startAction(Action.CAST, SLOT_CLIPS[slot]);
			if (actionClip.equals(SLOT_CLIPS[slot]) && actionTime == 0) {
				pendingSlot = slot;
				pendingTime = 0;
			}
		}
		if (pendingSlot >= 0) {
			pendingTime += dt;
			if (pendingSlot == 3 && in.channeling()) {
				pendingSlot = -1;
			} else if (pendingTime > CONFIRM_S) {
				HeroRenderer.LOG.info("Deadcraft: action {} cancelled: no cast came (on cooldown?)", action);
				pendingSlot = -1;
				actionDone = actionCancelled = true;
			}
		}
		if (in.channeling()) {
			startOrb();
			if (action == Action.ORB) orbChanneled = true;
		}
		// Reload: while Deadlock's weapon reloads (manual, or on its own when the magazine runs dry), at its
		// progress; the reload press only until that signal has been seen. In the air or sliding, the
		// running reload's motion (the "quick" in-air and slide clips looked wrong over a jump).
		if (in.reloading()) reloadSignalSeen = true;
		boolean reloadStarts = reloadSignalSeen ? in.reloading() && action != Action.RELOAD : (pressed & RELOAD) != 0;
		if (reloadStarts) {
			String clip = !in.grounded() || state == State.SLIDE || speed > MOVE_START ? "reload_run" : crouched ? "reload_crouch_idle" : "reload_idle";
			startAction(Action.RELOAD, clip);
		}
		// Melee: the press starts the heavy wind-up at once, paced to Deadlock's (learned); a tap's event then
		// turns it into a quick melee, a hold's event into the hit. Kept held, Deadlock strikes again each
		// cooldown: standing between, winding up again just before the next hit.
		sinceHeavyHit += dt;
		if ((b & MELEE) != 0) {
			boolean press = meleeHeld < 0;
			meleeHeld = press ? 0 : meleeHeld + dt;
			boolean repeat = !press && meleeThisPress && sinceHeavyHit < 4 && sinceHeavyHit >= heavyInterval - windupS;
			if ((press || repeat) && !(action == Action.MELEE && !actionDone)) {
				startAction(Action.MELEE, in.grounded() ? "melee_start" : "melee_in_air_start");
				heavy = action == Action.MELEE;
				if (heavy) actionRate = clipDuration(actionClip) / windupS;
			}
			meleeThisPress = true;
		} else if (meleeHeld >= 0) {
			pressLength = meleeHeld;
			meleeHeld = -1;
			meleeThisPress = false;
		}
		// Each shot: the first starts the shooting pose (and may cut a reload's fade), later ones restart the
		// recoil. Without shot events, the fire button.
		boolean newShot = shotPending || firing && !shotEventsSeen && action == Action.NONE;
		shotPending = false;
		if (newShot) {
			String set = crouched ? "shoot_crouch_" : "shoot_idle_";
			if (action == Action.SHOOT && !actionDone) switchClip(set + "loop", 0, RECOIL_FADE_S);
			else if (action == Action.NONE || actionDone) startAction(Action.SHOOT, set + "start");
		}
		if (action == Action.NONE) return;

		actionTime += dt;
		actionTravel += speed * dt;
		actionTopSpeed = (float) Math.max(actionTopSpeed, speed);
		// The heavy hit's tail (two small settling steps after the strike) at double speed: played as it is it
		// read as four steps; cut short, the feet slid back to the idle's stance.
		boolean settling = action == Action.MELEE && actionClip.endsWith("hit") && clipTime > HEAVY_STRIKE_S;
		clipTime += dt * (action == Action.SHOOT ? SHOOT_RATE : settling ? 2 : actionRate);
		if (action == Action.RELOAD && reloadSignalSeen && in.reload() >= 0) clipTime = in.reload() * clipDuration(actionClip);
		actionFade = Math.min(1, actionFade + dt / actionFadeS);
		float left = Float.MAX_VALUE;
		switch (action) {
			case SHOOT -> {
				// The start for the first shot (held on its last, aimed frame), then the loop, restarted by each
				// later shot and looping between.
				referenceClip = (crouched ? "shoot_crouch_" : "shoot_idle_") + "start";
				actionDone = !shooting;
			}
			case ORB -> {
				if (!actionDone && !in.channeling() && (orbChanneled || actionTime > 3)) {
					// Thrown: the release, crossfaded from wherever the raise or the hold had got to.
					switchClip(in.grounded() ? "ability_unicorn_dazzlingorb_end" : "ability_unicorn_dazzlingorb_inair_end", 0);
					actionDone = true;
				} else if (!actionDone && actionClip.equals(ORB_START) && clipTime >= clipDuration(ORB_START)) {
					switchClip("ability_unicorn_dazzlingorb_loop", 0);
				}
				if (actionDone) left = clipDuration(actionClip) - clipTime;
			}
			case RELOAD -> {
				// Over when the weapon says so (or at the clip's end without that signal).
				left = reloadSignalSeen ? (in.reloading() ? Float.MAX_VALUE : 0) : clipDuration(actionClip) - clipTime;
				if (left <= 0) actionDone = true;
			}
			case MELEE -> {
				// The heavy wind-up holds on its last frame until the hit (or gives up: released, no hit came). The
				// hit ends after the strike: the clip's tail is two settling steps Deadlock doesn't show (it read
				// as four steps: wind-up, lunge, step, step; Deadlock's is two and the horn strike).
				boolean hit = actionClip.endsWith("hit");
				left = heavy ? Float.MAX_VALUE : (hit ? Math.min(HEAVY_HIT_S, clipDuration(actionClip)) : clipDuration(actionClip)) - clipTime;
				if (heavy && actionTime > (meleeHeld < 0 ? windupS + 0.6f : HEAVY_MAX_WAIT_S)) {
					heavy = false;
					actionCancelled = actionDone = true;
				}
				if (left <= 0) actionDone = true;
			}
			default -> {
				left = clipDuration(actionClip) - clipTime;
				if (left <= 0) actionDone = true;
			}
		}
		float target = actionCancelled || action == Action.SHOOT && actionDone ? 0 : Math.max(0, Math.min(1, left / ACTION_OUT_S));
		actionWeight = approach(actionWeight, target, dt / (target > actionWeight ? ACTION_IN_S : ACTION_OUT_S));
		if (actionDone && actionWeight <= 0) {
			HeroRenderer.LOG.info("Deadcraft: action {} ended{}", action, action == Action.MELEE
				? String.format(" (%s: travelled %.1f blocks in %.2f s, top speed %.1f blocks/s)", actionClip, actionTravel, actionTime, actionTopSpeed) : "");
			action = Action.NONE;
		}
		// The whole body only standing still (crouched only for the clips with crouched versions); moving
		// or in the air, the action's motion goes on top of the movement.
		// Melee is a whole-body move everywhere: its clips spin the hips (153 deg) and swing the legs, on
		// the ground and in the air; without them the moving and in-air melee looked wrong.
		// Not reloads: their clips shuffle the left foot and lift the knee; the legs stay in the idle.
		boolean legs = action == Action.MELEE || action != Action.RELOAD && in.grounded()
			&& (state == State.IDLE || state == State.LAND || state == State.STOP || state == State.CROUCH_IDLE && action == Action.SHOOT);
		actionLegs = approach(actionLegs, legs ? 1 : 0, dt / 0.15f);
		clipLegs = approach(clipLegs, action == Action.MELEE && in.grounded() && speed > MELEE_STEP_SPEED ? 0 : 1, dt / 0.1f);
		orbWeight = approach(orbWeight, action == Action.ORB && !actionDone ? 1 : 0, dt / AIM_EASE_S);
	}

	/**
	 * Puts the action into {@link #top}. Standing still, its clip as it is (whole body). Moving or in the
	 * air, the spine takes the action's change from its own first pose over the movement, so the run (or
	 * the jump) carries on underneath (played as it is, a standing clip held the torso rigid over the
	 * legs), and the arms and the wand play the clip as it is (added as changes too, the wand came back
	 * from a reload toss to the wrong place over a jump, and a moving quick melee looked wrong).
	 */
	private void applyAction() {
		rig.followerDeltaWeight = 0;
		if (action == Action.NONE || actionWeight <= 0) return;
		HeroModel.Clip c = model.clips.get(actionClip), ref = model.clips.get(referenceClip);
		if (c == null) return;
		// The first shot's clip holds its last (aimed) frame until the next shot; the loop clip, restarted by each
		// later shot, loops until then (0.27 s long against Celeste's 0.58 s cycle: held, the hand froze).
		float time = action == Action.SHOOT && !c.loop() ? Math.min(clipTime, c.duration()) : clipTime;
		actionPose.clear();
		actionPose.add(c, time, 1);
		actionPose.finish();
		actionMix.copyFrom(actionPose);
		if (actionFade < 1) {
			actionMix.copyFrom(actionPrevious);
			actionMix.blendTowards(actionPose, smooth(actionFade));
		}
		float w = smooth(actionWeight);
		if (actionLegs < 1 && ref != null) {
			actionReference.clear();
			actionReference.add(ref, 0, 1);
			actionReference.finish();
			top.addDifference(actionMix, actionReference, w * (1 - actionLegs), spineChain);
			top.blendTowards(actionMix, w * (1 - actionLegs), limbs);
			// The arms now hang off a torso turned from the clip's (by the run underneath); the wand hangs off
			// the root and kept the clip's angle, outside the hand. Turn it with the torso too.
			if (chest >= 0) {
				model.chainWorld(top, top, null, chest, chestNow);
				// (The clip's wand goes with the clip's whole body, hips included: its chest entirely from the clip.)
				model.chainWorld(actionMix, actionMix, null, chest, chestClip);
				HeroModel.relative(chestNow, chestClip, rig.followerDelta);
				rig.followerDeltaWeight = w * (1 - actionLegs);
			}
		}
		if (actionLegs > 0) {
			// A melee on the move (the heavy one lunges): the legs keep running, so she steps instead of
			// sliding along on the clip's planted feet; the hips and body still spin with the clip.
			top.blendTowards(actionMix, w * actionLegs, notLegs);
			top.blendTowards(actionMix, w * actionLegs * clipLegs, legs);
		}
	}

	/**
	 * The aim: the change from each aim set's centre pose to its up or down pose, by the camera's pitch,
	 * for the stance and posture (and Celeste's orb while she holds it).
	 */
	private void applyAim(Input in, float dt) {
		pitch = in.pitch();
		boolean moving = switch (state) {
			case IDLE, RUN, CROUCH_IDLE, CROUCH_RUN, LAND, STOP, SLIDE, FALL, JUMP, AIR_JUMP -> true;
			default -> false;
		};
		aimWeight = approach(aimWeight, moving || orbWeight > 0 ? 1 : 0, dt / AIM_EASE_S);
		float p = Math.max(-1, Math.min(1, pitch / AIM_RANGE));
		float amount = Math.abs(p) * aimWeight;
		if (amount <= 0) return;
		String dir = p < 0 ? "_up" : "_down";
		String weaponSet = switch (state) {
			case CROUCH_IDLE, CROUCH_RUN -> "aim_weapon_crouch";
			case SLIDE -> "aim_weapon_slide";
			case RUN, FALL, JUMP, AIR_JUMP -> "aim_weapon_run";
			default -> "aim_weapon_idle";
		};
		float rest = 1 - orbWeight;
		// Out of combat only the head and shoulders follow (there is no such set for a slide).
		if (state != State.SLIDE) aimLayer("aim_out_of_combat_idle", dir, amount * rest * (1 - stance));
		aimLayer(weaponSet, dir, amount * rest * stance);
		aimLayer("aim_dazzling_orb", dir, amount * orbWeight);
	}

	/**
	 * While a reload, melee or cast owns the arms and the wand, only the neck and head aim: aimed arms
	 * moved her hand up to 0.7 m with the pitch, off the path of the reload's wand toss (it came back to
	 * the wrong place, worst in jumps, looking up or down).
	 */
	private void aimLayer(String set, String dir, float weight) {
		HeroModel.Clip centre = model.clips.get(set), to = model.clips.get(set + dir);
		if (weight <= 0 || centre == null || to == null) return;
		float busy = action == Action.RELOAD || action == Action.MELEE || action == Action.CAST ? smooth(actionWeight) : 0;
		// At a fixed frame: the aim clips are poses, and looping them (every 2 s, out of step with the idle)
		// showed as a snap in the standing idle whenever the camera looked up or down.
		top.addLayer(to, centre, 0, weight * (1 - busy), aimed);
		top.addLayer(to, centre, 0, weight * busy, aimHead);
	}

	private static float approach(float value, float target, float step) {
		return value < target ? Math.min(target, value + step) : Math.max(target, value - step);
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

	/** How strongly the current action shows (for tests). */
	float actionWeight() {
		return action == Action.NONE ? 0 : actionWeight;
	}

	/** Everything about the current blend, for the spike report. */
	String debugLine() {
		return String.format("state %s for %.2f s (fade %.2f of %.2f s), move %s, clips %s/%s %.2f, dash %s, mantle %s, wall %s, look %.0f, turn %.0f, "
			+ "speed %.1f, %s, eye %.0f, hull %.0f, air %.2f s, action %s %s %.2f s (weight %.2f, legs %.2f%s), stance %.2f, pitch %.0f, aim %.2f",
			state, stateTime, fade, fadeTime, move, clipA, clipB, blend, dashClip, mantleClip, wallSide, look, turnRate, speed,
			grounded ? "ground" : "air", eye, hull, airTime, action, actionClip, actionTime, actionWeight, actionLegs, actionDone ? ", ending" : "",
			stance, pitch, aimWeight);
	}

	/** For the test HUD. */
	public String hudLine() {
		return String.format("anim: %s%s  speed %.1f b/s  %s  eye %.0f/%.0f  hull %.0f/%.0f  look %.0f  pitch %.0f  stance %.1f%s", state,
			state == State.DASH ? " " + dashClip : "", speed, grounded ? "ground" : "air", eye, standingEye, hull, standingHull, look, pitch, stance,
			action == Action.NONE ? "" : "  " + action + " " + actionClip);
	}

	public String describe() {
		return state + (state == State.RUN || state == State.CROUCH_RUN ? String.format(" %s/%s %.2f", clipA, clipB, blend) : "")
			+ (state == State.DASH ? " " + dashClip : "") + String.format(" standing eye %.0f", standingEye);
	}
}
