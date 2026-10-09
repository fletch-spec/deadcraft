package dev.deadcraft.client;

import java.util.ArrayDeque;

/**
 * Smooth playback of the hero's 64 Hz samples at whatever rate Minecraft draws.
 *
 * <p>Taking the newest sample each frame makes the camera judder: at 120 fps some frames repeat a
 * sample and others jump two. Instead every sample is put on the Deadlock server's clock, and each
 * frame is drawn at "now on that clock, minus a small delay", interpolating between the two samples
 * around that moment. The delay covers the gap between samples plus arrival jitter.
 *
 * <p>Positions are hero-frame blocks; angles are degrees (yaw interpolates the short way round).
 */
final class HeroTimeline {
	/** How far behind the newest sample frames are drawn: one 64 Hz tick plus margin for jitter. */
	static final double DELAY_S = 0.022;
	/** Never extrapolate further than this past the newest sample. */
	private static final double MAX_EXTRAPOLATE_S = 0.030;
	private static final int KEEP = 16;
	/** How fast the clock offset estimate follows a later (slower) arrival, per sample. */
	private static final double OFFSET_RISE = 0.01;

	record Sample(long tick, double serverTime, double x, double y, double z, float yaw, float pitch,
		double vx, double vy, double vz) {}

	record Pose(double x, double y, double z, float yaw, float pitch) {}

	private final ArrayDeque<Sample> samples = new ArrayDeque<>();
	private double delay = DELAY_S;
	/**
	 * Adaptive: the delay follows how late samples actually arrive (Deadlock's server doesn't tick
	 * perfectly evenly), so frames rarely run past the newest sample. Off in tests and when the delay
	 * is set by hand.
	 */
	private boolean adaptive;
	private double latenessPeak, lastAddLocal;
	private static final double TICK_S = 1 / 64.0, PEAK_DECAY_S = 2.0, MIN_DELAY_S = 0.018, MAX_DELAY_S = 0.06;
	private int dryFrames, frames;
	/** local clock - server clock, tracking the earliest arrivals (the least delayed ones). */
	private double offset = Double.NaN;

	void clear() {
		samples.clear();
		offset = Double.NaN;
	}

	/** Adds a sample if its tick is new. {@code localNow} in seconds. */
	void add(Sample s, double localNow) {
		Sample last = samples.peekLast();
		if (last != null && s.tick() <= last.tick()) {
			if (s.tick() < last.tick()) clear();  // the server restarted or ticks went backwards
			else return;
		}
		double o = localNow - s.serverTime();
		// An early arrival means less delay: take it at once. A late one may be jitter: drift slowly.
		offset = Double.isNaN(offset) || o < offset ? o : offset + (o - offset) * OFFSET_RISE;
		samples.addLast(s);
		while (samples.size() > KEEP) samples.removeFirst();
		double lateness = o - offset;  // how much later than the earliest-arriving samples
		if (lastAddLocal > 0) latenessPeak *= Math.exp(-(localNow - lastAddLocal) / PEAK_DECAY_S);
		latenessPeak = Math.max(latenessPeak, lateness);
		lastAddLocal = localNow;
		if (adaptive) delay = Math.max(MIN_DELAY_S, Math.min(MAX_DELAY_S, TICK_S + latenessPeak + 0.003));
	}

	/** Shifts every stored sample, for a recentre (the hero and the world moved together). */
	void shift(double dx, double dy, double dz) {
		ArrayDeque<Sample> moved = new ArrayDeque<>(samples.size());
		for (Sample s : samples) {
			moved.addLast(new Sample(s.tick(), s.serverTime(), s.x() + dx, s.y() + dy, s.z() + dz, s.yaw(), s.pitch(), s.vx(), s.vy(), s.vz()));
		}
		samples.clear();
		samples.addAll(moved);
	}

	/** Seconds behind the newest sample that frames are drawn (DELAY_S by default). */
	void setDelay(double seconds) {
		delay = seconds;
		adaptive = false;
	}

	void setAdaptive() {
		adaptive = true;
	}

	boolean adaptive() {
		return adaptive;
	}

	/** Frames drawn past the newest sample (extrapolated) since the last call, and frames in all. */
	int[] takeDryFrames() {
		int[] r = {dryFrames, frames};
		dryFrames = frames = 0;
		return r;
	}

	double delay() {
		return delay;
	}

	/** When a sample stamped {@code serverTime} reached us, in local seconds. */
	double localTime(double serverTime) {
		return serverTime + offset;
	}

	boolean isEmpty() {
		return samples.isEmpty();
	}

	/** The pose to draw at local time {@code localNow} (seconds). */
	Pose poseAt(double localNow) {
		Sample newest = samples.peekLast();
		double t = localNow - offset - delay;
		frames++;
		if (t >= newest.serverTime()) {
			dryFrames++;
			double dt = Math.min(t - newest.serverTime(), MAX_EXTRAPOLATE_S);
			return new Pose(newest.x() + newest.vx() * dt, newest.y() + newest.vy() * dt, newest.z() + newest.vz() * dt,
				newest.yaw(), newest.pitch());
		}
		Sample before = null;
		for (Sample s : samples) {
			if (s.serverTime() <= t) {
				before = s;
				continue;
			}
			if (before == null) return pose(s);  // older than anything we kept
			double f = (t - before.serverTime()) / (s.serverTime() - before.serverTime());
			return new Pose(lerp(before.x(), s.x(), f), lerp(before.y(), s.y(), f), lerp(before.z(), s.z(), f),
				lerpAngle(before.yaw(), s.yaw(), f), (float) lerp(before.pitch(), s.pitch(), f));
		}
		return pose(newest);
	}

	private static Pose pose(Sample s) {
		return new Pose(s.x(), s.y(), s.z(), s.yaw(), s.pitch());
	}

	private static double lerp(double a, double b, double f) {
		return a + (b - a) * f;
	}

	private static float lerpAngle(float a, float b, double f) {
		double d = ((b - a) % 360 + 540) % 360 - 180;  // shortest signed difference
		return (float) (a + d * f);
	}
}
