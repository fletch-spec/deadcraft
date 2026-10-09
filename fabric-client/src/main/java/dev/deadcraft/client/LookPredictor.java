package dev.deadcraft.client;

/**
 * Turns Minecraft's camera straight from the mouse, kept true to Deadlock's own angles.
 *
 * <p>Deadlock's angles reach us about 70 ms after the mouse moved (client, usercmd, server tick, shared
 * memory), then the timeline adds its smoothing delay. The raw mouse counts are known at once, and
 * Deadlock turns by a fixed number of degrees per count (its sensitivity, raw input, no acceleration).
 * So the camera is drawn at {@code bias + gain * counts(now)}, where every Deadlock sample measures the
 * bias as {@code angle - gain * counts(sampleTime - lag)} and the bias is lightly filtered. While the
 * model holds, the bias is constant and the camera moves with the mouse at the frame rate; when it
 * doesn't (a menu open, sensitivity changed, respawn), the bias pulls the camera back onto Deadlock's
 * angle within a few samples.
 *
 * <p>The gain (degrees per count, signed) and the lag are learned from the stream itself: for each
 * candidate lag, a decaying least-squares fit of Deadlock's per-sample turn against the mouse counts over
 * the same interval shifted by that lag; the lag that explains the most of the turning wins. Until that
 * fit is good, {@link #predict} reports nothing and the caller keeps the interpolated Deadlock angles;
 * after that the last good calibration is kept until {@link #reset}.
 *
 * <p>Angles are degrees, Source convention (yaw grows to the left, pitch positive looking down).
 */
final class LookPredictor {
	interface Counts {
		/** Cumulative mouse counts {x, y} reported at or before local time {@code nanos}. */
		long[] countsAt(long nanos);
	}

	record Look(float yaw, float pitch) {}

	static final int LAG_STEP_MS = 4, LAGS = 31;  // 0..120 ms
	private static final double DECAY = 0.998;  // per sample: ~8 s memory at 64 Hz
	private static final double MIN_TURN_VARIANCE = 400;  // decayed sum of squared per-sample turns (degrees^2)
	private static final double MIN_SCORE = 0.85;
	private static final double BIAS_TIME_S = 0.08;
	private static final double SNAP_DEGREES = 20;
	private static final double MAX_SAMPLE_TURN = 30;  // a bigger one-sample turn is a respawn or teleport
	private static final double MAX_SAMPLE_GAP_S = 0.1;
	private static final float PITCH_LIMIT = 89f;

	private final Counts counts;
	private final double[] sxyYaw = new double[LAGS], sxxYaw = new double[LAGS];
	private final double[] sxyPitch = new double[LAGS], sxxPitch = new double[LAGS];
	// The gain is fitted again over quarter-second windows at each lag: Deadlock's tick-to-tick timing
	// jitter only touches a window's ends, so these are far less noisy than the per-sample fit, which
	// underestimates the gain (noise in the counts biases a least-squares slope towards zero).
	private static final int WINDOW_SAMPLES = 16;
	private static final double WINDOW_DECAY = 0.98;  // per window: ~12 s memory
	private final double[] winDx = new double[LAGS], winDy = new double[LAGS];
	private final double[] gxyYaw = new double[LAGS], gxxYaw = new double[LAGS], gxyPitch = new double[LAGS], gxxPitch = new double[LAGS];
	private double winTurn, winPitch;
	private int winSamples;
	private boolean winValid = true, winPitchValid = true;
	private double syyYaw, syyPitch;

	private boolean havePrevious;
	private double prevTime, prevYaw, prevPitch;  // prevYaw unwrapped
	private double unwrappedYaw;

	private int lag = -1;
	private double gainYaw, gainPitch, score;
	private boolean biasValid;
	private double biasYaw, biasPitch;

	LookPredictor(Counts counts) {
		this.counts = counts;
	}

	void clear() {
		havePrevious = false;
		biasValid = false;
	}

	/** Forgets the calibration as well (Deadlock's sensitivity may have changed). */
	void reset() {
		clear();
		java.util.Arrays.fill(sxyYaw, 0);
		java.util.Arrays.fill(sxxYaw, 0);
		java.util.Arrays.fill(sxyPitch, 0);
		java.util.Arrays.fill(sxxPitch, 0);
		syyYaw = syyPitch = 0;
		for (double[] a : new double[][] {winDx, winDy, gxyYaw, gxxYaw, gxyPitch, gxxPitch}) java.util.Arrays.fill(a, 0);
		winTurn = winPitch = 0;
		winSamples = 0;
		lag = -1;
		score = 0;
	}

	boolean calibrated() {
		return lag >= 0;
	}

	/**
	 * A new Deadlock sample: {@code time} is when it reached us, local seconds on the same clock as
	 * {@code nanos / 1e9} given to {@link Counts}.
	 */
	void addSample(double time, float yaw, float pitch) {
		if (havePrevious) {
			double dt = time - prevTime;
			if (dt <= 0) return;
			double turn = wrap(yaw - wrap(prevYaw));
			unwrappedYaw = prevYaw + turn;
			double dPitch = pitch - prevPitch;
			if (dt < MAX_SAMPLE_GAP_S && Math.abs(turn) < MAX_SAMPLE_TURN && Math.abs(dPitch) < MAX_SAMPLE_TURN) {
				fit(time, dt, turn, dPitch, Math.abs(pitch) < PITCH_LIMIT - 4 && Math.abs(prevPitch) < PITCH_LIMIT - 4);
			} else {
				winValid = false;
			}
		} else {
			unwrappedYaw = yaw;
		}
		havePrevious = true;
		prevTime = time;
		prevYaw = unwrappedYaw;
		prevPitch = pitch;
		if (lag < 0) return;

		long[] c = counts.countsAt(nanos(time - lag * LAG_STEP_MS / 1000.0));
		double mYaw = unwrappedYaw - gainYaw * c[0], mPitch = pitch - gainPitch * c[1];
		double gap = biasValid ? time - lastBiasTime : 0;
		double error = Math.abs(mYaw - biasYaw);
		if (biasValid) {
			errorSum += error;
			errorMax = Math.max(errorMax, error);
			errorSamples++;
			hudErrorSum += error;
			hudErrorMax = Math.max(hudErrorMax, error);
			hudSamples++;
		}
		if (!biasValid || gap > MAX_SAMPLE_GAP_S || error > SNAP_DEGREES) {
			if (biasValid) {
				snaps++;
				hudSnaps++;
			}
			biasYaw = mYaw;
		} else {
			biasYaw += (mYaw - biasYaw) * (1 - Math.exp(-gap / BIAS_TIME_S));
		}
		// Deadlock clamps pitch; past the clamp the mouse keeps counting, so pitch measurements there say
		// nothing about the bias except "at least this far". Snap and let the output clamp handle it.
		if (!biasValid || gap > MAX_SAMPLE_GAP_S || Math.abs(mPitch - biasPitch) > SNAP_DEGREES || Math.abs(pitch) >= PITCH_LIMIT - 1) {
			biasPitch = mPitch;
		} else {
			biasPitch += (mPitch - biasPitch) * (1 - Math.exp(-gap / BIAS_TIME_S));
		}
		biasValid = true;
		lastBiasTime = time;
	}

	private double lastBiasTime;
	private double errorSum, errorMax;
	private int errorSamples, snaps;
	private double hudErrorSum, hudErrorMax;
	private int hudSamples, hudSnaps;

	/** One line for the test HUD, covering the time since the last call. */
	String hudLine() {
		String s = lag < 0 ? String.format("look: learning (fit %.2f)", score)
			: String.format("look: lag %d ms, gain %.4f, correction avg %.1f max %.1f deg, %d snaps", lag * LAG_STEP_MS, gainYaw,
				hudSamples == 0 ? 0 : hudErrorSum / hudSamples, hudErrorMax, hudSnaps);
		hudErrorSum = hudErrorMax = 0;
		hudSamples = hudSnaps = 0;
		return s;
	}

	/** The look to draw at local time {@code nanos}, or null when not calibrated yet. */
	Look predict(long nanos) {
		if (lag < 0 || !biasValid) return null;
		long[] c = counts.countsAt(nanos);
		double yaw = biasYaw + gainYaw * c[0];
		double pitch = Math.max(-PITCH_LIMIT, Math.min(PITCH_LIMIT, biasPitch + gainPitch * c[1]));
		return new Look((float) wrap(yaw), (float) pitch);
	}

	private void fit(double time, double dt, double turn, double dPitch, boolean pitchUsable) {
		syyYaw = syyYaw * DECAY + turn * turn;
		if (pitchUsable) syyPitch = syyPitch * DECAY + dPitch * dPitch;
		for (int i = 0; i < LAGS; i++) {
			double shift = i * LAG_STEP_MS / 1000.0;
			long[] a = counts.countsAt(nanos(time - dt - shift)), b = counts.countsAt(nanos(time - shift));
			double dx = b[0] - a[0], dy = b[1] - a[1];
			winDx[i] += dx;
			winDy[i] += dy;
			sxyYaw[i] = sxyYaw[i] * DECAY + dx * turn;
			sxxYaw[i] = sxxYaw[i] * DECAY + dx * dx;
			if (pitchUsable) {
				sxyPitch[i] = sxyPitch[i] * DECAY + dy * dPitch;
				sxxPitch[i] = sxxPitch[i] * DECAY + dy * dy;
			}
		}
		winTurn += turn;
		winPitch += dPitch;
		if (!pitchUsable) winPitchValid = false;
		if (++winSamples >= WINDOW_SAMPLES) {
			for (int i = 0; i < LAGS; i++) {
				if (winValid) {
					gxyYaw[i] = gxyYaw[i] * WINDOW_DECAY + winDx[i] * winTurn;
					gxxYaw[i] = gxxYaw[i] * WINDOW_DECAY + winDx[i] * winDx[i];
				}
				if (winValid && winPitchValid) {
					gxyPitch[i] = gxyPitch[i] * WINDOW_DECAY + winDy[i] * winPitch;
					gxxPitch[i] = gxxPitch[i] * WINDOW_DECAY + winDy[i] * winDy[i];
				}
			}
			java.util.Arrays.fill(winDx, 0);
			java.util.Arrays.fill(winDy, 0);
			winTurn = winPitch = 0;
			winSamples = 0;
			winValid = winPitchValid = true;
		}
		if (syyYaw < MIN_TURN_VARIANCE) return;
		int best = -1;
		double bestScore = 0;
		for (int i = 0; i < LAGS; i++) {
			// Share of all turning (yaw and pitch) that the counts explain at this lag.
			double explained = (sxxYaw[i] > 0 ? sxyYaw[i] * sxyYaw[i] / sxxYaw[i] : 0)
				+ (sxxPitch[i] > 0 ? sxyPitch[i] * sxyPitch[i] / sxxPitch[i] : 0);
			double s = explained / (syyYaw + syyPitch);
			if (s > bestScore) {
				bestScore = s;
				best = i;
			}
		}
		score = bestScore;
		// A poor fit (Deadlock's menu open, mouse moving without turning the hero) keeps the last good
		// calibration; the bias still pulls the camera onto Deadlock's angle every sample.
		if (best < 0 || bestScore < MIN_SCORE) return;
		lag = best;
		// Windowed gain once a few windows of turning are in (counts squared: ~10 cm of mouse travel).
		gainYaw = gxxYaw[best] > 1e6 ? gxyYaw[best] / gxxYaw[best] : sxyYaw[best] / sxxYaw[best];
		// Pitch barely moved yet: Source uses the same degrees per count both ways (m_pitch = m_yaw),
		// with mouse-down turning down (positive) while mouse-right turns yaw negative.
		if (gxxPitch[best] > 1e6) gainPitch = gxyPitch[best] / gxxPitch[best];
		else gainPitch = syyPitch > MIN_TURN_VARIANCE / 4 && sxxPitch[best] > 0 ? sxyPitch[best] / sxxPitch[best] : -gainYaw;
	}

	String describe() {
		if (lag < 0) {
			return String.format("look: learning from the mouse (fit %.2f, turned %.0f deg^2 of %.0f needed)", score, syyYaw, MIN_TURN_VARIANCE);
		}
		String s = String.format("look: raw mouse, lag %d ms, %.4f / %.4f deg per count (yaw/pitch), fit now %.3f; correction avg %.2f max %.1f deg, %d snaps",
			lag * LAG_STEP_MS, gainYaw, gainPitch, score, errorSamples == 0 ? 0 : errorSum / errorSamples, errorMax, snaps);
		errorSum = errorMax = 0;
		errorSamples = snaps = 0;
		return s;
	}

	private static long nanos(double seconds) {
		return (long) (seconds * 1e9);
	}

	static double wrap(double degrees) {
		return ((degrees % 360) + 540) % 360 - 180;
	}
}
