package dev.deadcraft.client.hero;

import java.util.Arrays;

/**
 * Picks and blends the hero's clips from what Deadlock reports about the hero, standing in for
 * Deadlock's own animation graph: idle, eight-way run (crouched or not) with the play rate following
 * the speed, jump and fall, slide, and dash. A new state fades in over {@link #FADE_S}.
 *
 * <p>Deadlock doesn't send slide or dash flags, so they are read from the motion: crouched (eye
 * height well below standing) and fast is a slide; a burst well above run speed is a dash.
 */
public final class HeroAnimator {
	/** One frame of what the hero is doing. Speeds in blocks per second. */
	public record Input(double forward, double right, double up, boolean grounded, float eyeHeight) {}

	static final float FADE_S = 0.15f;
	/** Celeste's run speed, blocks/s; the run clips play at normal rate here. Tuned from the log. */
	static float runSpeed = 4.2f;
	static float dashFactor = 1.6f;
	static float slideFactor = 1.1f;

	private static final String[] DIRS = {"n", "ne", "e", "se", "s", "sw", "w", "nw"};

	enum State { IDLE, RUN, CROUCH_IDLE, CROUCH_RUN, JUMP, FALL, SLIDE, DASH }

	private final HeroModel model;
	private State state = State.IDLE, previous;
	private float stateTime, fade = 1;
	private float runPhase;  // 0..1, shared by every direction so blends stay in step
	private float[] previousPose;
	private final float[] pose;
	private float standingEye;
	private Input last;
	private String clipA, clipB;
	private float blend, previousStateTime;

	public HeroAnimator(HeroModel model) {
		this.model = model;
		pose = new float[model.jointCount * 12];
	}

	public State state() {
		return state;
	}

	/** Advances by {@code dt} seconds and returns the skinning matrices (jointCount * 12). */
	public float[] update(Input in, float dt) {
		standingEye = Math.max(standingEye, in.eyeHeight());
		double speed = Math.hypot(in.forward(), in.right());
		boolean crouched = standingEye > 0 && in.eyeHeight() < standingEye * 0.8f;
		State next = choose(in, speed, crouched);
		if (next != state) {
			previousPose = pose.clone();
			previous = state;
			state = next;
			stateTime = 0;
			fade = 0;
		}
		stateTime += dt;
		fade = Math.min(1, fade + dt / FADE_S);
		if (state == State.RUN || state == State.CROUCH_RUN) {
			float rate = (float) Math.max(0.5, Math.min(2.0, speed / (state == State.RUN ? runSpeed : runSpeed * 0.5)));
			HeroModel.Clip c = model.clips.get(state == State.RUN ? "out_of_combat_run_n" : "out_of_combat_crouch_run_n");
			if (c != null) runPhase = (runPhase + dt * rate / c.duration()) % 1f;
		}

		Arrays.fill(pose, 0);
		sample(in, speed);
		if (fade < 1 && previousPose != null) {
			float a = smooth(fade);
			for (int i = 0; i < pose.length; i++) pose[i] = previousPose[i] * (1 - a) + pose[i] * a;
		}
		last = in;
		return pose;
	}

	private State choose(Input in, double speed, boolean crouched) {
		boolean moving = speed > 0.3;
		if (!in.grounded()) {
			if (state == State.DASH && stateTime < clipDuration("dash_air_forward")) return State.DASH;
			if (speed > runSpeed * dashFactor && state != State.SLIDE) return State.DASH;
			if (state == State.JUMP && stateTime < clipDuration("jump_ground")) return State.JUMP;
			// Leaving the ground going up is a jump; otherwise a fall.
			if (state != State.JUMP && state != State.FALL && in.up() > 2) return State.JUMP;
			return State.FALL;
		}
		if (crouched && speed > runSpeed * slideFactor) return State.SLIDE;
		if (state == State.DASH && stateTime < clipDuration("dash_ground")) return State.DASH;
		if (!crouched && speed > runSpeed * dashFactor && (state == State.RUN || state == State.IDLE || state == State.DASH)) return State.DASH;
		if (crouched) return moving ? State.CROUCH_RUN : State.CROUCH_IDLE;
		return moving ? State.RUN : State.IDLE;
	}

	private void sample(Input in, double speed) {
		switch (state) {
			case IDLE -> play("out_of_combat_stand_idle", stateTime, 1);
			case CROUCH_IDLE -> play("out_of_combat_crouch_idle", stateTime, 1);
			case RUN -> directional("out_of_combat_run_", in);
			case CROUCH_RUN -> directional("out_of_combat_crouch_run_", in);
			case JUMP -> play("jump_ground", stateTime, 1);
			case FALL -> play("in_air_loop_down", stateTime, 1);
			case SLIDE -> {
				float start = clipDuration("slide_start");
				if (stateTime < start) play("slide_start", stateTime, 1);
				else play("slide_loop", stateTime - start, 1);
			}
			case DASH -> {
				if (in.grounded()) play("dash_ground", stateTime, 1);
				else play("dash_air_" + airDirection(in), stateTime, 1);
			}
		}
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

	private void play(String clip, float time, float weight) {
		HeroModel.Clip c = model.clips.get(clip);
		if (c == null) c = model.clips.get("out_of_combat_stand_idle");
		if (c != null) model.accumulate(c, time, weight, pose);
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

	public String describe() {
		return state + (state == State.RUN || state == State.CROUCH_RUN ? String.format(" %s/%s %.2f", clipA, clipB, blend) : "")
			+ String.format(" standing eye %.0f", standingEye);
	}
}
