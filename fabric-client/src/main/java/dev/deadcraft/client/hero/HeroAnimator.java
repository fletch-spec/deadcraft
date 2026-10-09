package dev.deadcraft.client.hero;

import java.util.Arrays;

/**
 * Picks and blends the hero's clips, standing in for Deadlock's own animation graph. Continuous
 * states come from the hero's motion: idle, eight-way run (crouched or not) with the play rate
 * following the speed, and fall. One-shot moves come from Deadlock's movement ability events (dash,
 * slide, mantle, jump and air jump), which say exactly when they start. A new state fades in over
 * {@link #FADE_S}.
 */
public final class HeroAnimator {
	/** One frame of what the hero is doing. Speeds in blocks per second, relative to facing. */
	public record Input(double forward, double right, double up, boolean grounded, float eyeHeight, float hullHeight) {}

	static final float FADE_S = 0.15f;
	/** Speed at which the run clips play at normal rate, blocks/s. */
	static float runSpeed = 5f;
	/** Deadlock's dash is a short burst; the clip's tail is a recovery to standing, which we skip. */
	static final float DASH_MAX_S = 0.4f;
	/** Body turn speed (degrees/s) at which standing feet fully step. */
	static final float TURN_STEP_RATE = 240f;

	private static final String[] DIRS = {"n", "ne", "e", "se", "s", "sw", "w", "nw"};

	enum State { IDLE, RUN, CROUCH_IDLE, CROUCH_RUN, JUMP, AIR_JUMP, FALL, SLIDE, DASH, MANTLE }

	private final HeroModel model;
	private State state = State.IDLE;
	/** A move started by an ability event, held until it ends. */
	private State move;
	private float stateTime, fade = 1, airTime;
	private float runPhase;  // 0..1, shared by every direction so blends stay in step
	private float[] previousPose;
	private final float[] pose;
	private float standingEye, eye, slideEndedAt = -10, clock, turnRate, turnPhase, standingHull, hull;
	/** Whether this slide lowered the hull: then the hull says when it ends; otherwise the speed does. */
	private boolean slideHullLow;
	private double speed;
	private boolean grounded;
	private String dashClip = "dash_ground", clipA = "", clipB = "";
	private float blend;

	public HeroAnimator(HeroModel model) {
		this.model = model;
		pose = new float[model.jointCount * 12];
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

	/** A Deadlock ability was used (from the plugin's event ring). Movement abilities start moves. */
	public void ability(String name, Input in) {
		String n = name.toLowerCase();
		if (n.contains("dash")) {
			dashClip = in.grounded() ? "dash_ground" : "dash_air_" + airDirection(in);
			start(State.DASH);
		} else if (n.contains("slide")) {
			// The slide ability fires on every crouch press; it's a slide only when moving fast.
			if (move != State.SLIDE && in.grounded() && Math.hypot(in.forward(), in.right()) > runSpeed * 0.6) {
				slideHullLow = false;
				start(State.SLIDE);
			}
		} else if (n.contains("mantle")) {
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
		previousPose = pose.clone();
		state = next;
		stateTime = 0;
		fade = 0;
	}

	/** Advances by {@code dt} seconds and returns the skinning matrices (jointCount * 12). */
	public float[] update(Input in, float dt) {
		standingEye = Math.max(standingEye, in.eyeHeight());
		eye = in.eyeHeight();
		hull = in.hullHeight();
		standingHull = Math.max(standingHull, hull);
		clock += dt;
		turnPhase = (turnPhase + dt * Math.min(1f, Math.abs(turnRate) / TURN_STEP_RATE) * 1.5f) % 1f;
		grounded = in.grounded();
		airTime = in.grounded() ? 0 : airTime + dt;
		speed = Math.hypot(in.forward(), in.right());
		if (state == State.SLIDE && hull < standingHull * 0.85f) slideHullLow = true;
		boolean crouched = standingEye > 0 && in.eyeHeight() < standingEye * 0.8f;
		if (move != null && !moveContinues(in, speed)) {
			if (move == State.SLIDE) slideEndedAt = clock;
			move = null;
		}
		State next = move != null ? move : choose(in, speed, crouched);
		if (next != state) enter(next);
		stateTime += dt;
		fade = Math.min(1, fade + dt / FADE_S);
		if (state == State.RUN || state == State.CROUCH_RUN) {
			float rate = (float) Math.max(0.5, Math.min(2.0, speed / (state == State.RUN ? runSpeed : runSpeed * 0.5)));
			HeroModel.Clip c = model.clips.get(state == State.RUN ? "out_of_combat_run_n" : "out_of_combat_crouch_run_n");
			if (c != null) runPhase = (runPhase + dt * rate / c.duration()) % 1f;
		}

		Arrays.fill(pose, 0);
		sample(in);
		if (fade < 1 && previousPose != null) {
			float a = smooth(fade);
			for (int i = 0; i < pose.length; i++) pose[i] = previousPose[i] * (1 - a) + pose[i] * a;
		}
		return pose;
	}

	private boolean moveContinues(Input in, double speed) {
		return switch (move) {
			case DASH -> stateTime < Math.min(DASH_MAX_S, clipDuration(dashClip));
			case MANTLE -> stateTime < clipDuration("mantle_64");
			// A slide ends when Deadlock stands the hero up (hull back to full height), drops it into a
			// crouch (eye at crouch height), or it leaves the ground; without a hull change, when it slows.
			case SLIDE -> stateTime < 0.15f || in.grounded() && stateTime < 4f && in.eyeHeight() > standingEye * 0.8f
				&& (slideHullLow ? hull < standingHull * 0.9f : speed > runSpeed * 0.9);
			case JUMP -> stateTime < clipDuration("jump_ground") && !(in.grounded() && stateTime > 0.2f);
			case AIR_JUMP -> stateTime < clipDuration("jump_air") && !in.grounded();
			default -> false;
		};
	}

	private State choose(Input in, double speed, boolean crouched) {
		if (!in.grounded()) {
			// Walked off an edge (no jump event): fall after a moment, so steps down don't flicker.
			return airTime > 0.15f || state == State.FALL ? State.FALL : state;
		}
		boolean moving = speed > 0.3;
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
			case MANTLE -> play("mantle_64", stateTime);
			case DASH -> play(dashClip, stateTime);
			case SLIDE -> {
				float start = clipDuration("slide_start");
				if (stateTime < start) play("slide_start", stateTime);
				else play("slide_loop", stateTime - start);
			}
		}
	}

	/**
	 * Standing still: the idle, with sideways steps blended in while the body turns on the spot
	 * (Celeste has no standing turn clips; the strafe run at a slow phase reads as stepping round).
	 */
	private void idle(String idleClip, String runPrefix) {
		float w = Math.min(1f, Math.abs(turnRate) / TURN_STEP_RATE) * 0.7f;
		HeroModel.Clip c = model.clips.get(idleClip);
		if (c != null) model.accumulate(c, stateTime, 1 - w, pose);
		if (w > 0) playPhase(runPrefix + (turnRate > 0 ? "e" : "w"), turnPhase, w);
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
		if (c != null) model.accumulate(c, time, 1, pose);
	}

	private void playPhase(String clip, float phase, float weight) {
		HeroModel.Clip c = model.clips.get(clip);
		if (c != null) model.accumulate(c, phase * c.duration(), weight, pose);
	}

	private float clipDuration(String clip) {
		HeroModel.Clip c = model.clips.get(clip);
		return c == null ? 0 : c.duration();
	}

	private static float smooth(float x) {
		return x * x * (3 - 2 * x);
	}

	/** For the test HUD. */
	public String hudLine() {
		return String.format("anim: %s%s  speed %.1f b/s  %s  eye %.0f/%.0f  hull %.0f/%.0f", state, state == State.DASH ? " " + dashClip : "", speed,
			grounded ? "ground" : "air", eye, standingEye, hull, standingHull);
	}

	public String describe() {
		return state + (state == State.RUN || state == State.CROUCH_RUN ? String.format(" %s/%s %.2f", clipA, clipB, blend) : "")
			+ (state == State.DASH ? " " + dashClip : "") + String.format(" standing eye %.0f", standingEye);
	}
}
