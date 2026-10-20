package dev.deadcraft.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class HeroTimelineTest {
	private static final double TICK = 1.0 / 64;

	private static HeroTimeline.Sample at(long tick, double x, float yaw) {
		return new HeroTimeline.Sample(tick, tick * TICK, x, 0, 0, yaw, 0, 0, 0, 0);
	}

	@Test
	void drawsBetweenSamplesAtAFixedDelay() {
		HeroTimeline t = new HeroTimeline();
		// Samples arrive exactly on time: local clock = server clock + 100 s.
		for (long tick = 0; tick <= 4; tick++) t.add(at(tick, tick, 0), 100 + tick * TICK);
		double now = 100 + 4 * TICK;
		// Drawn DELAY_S behind the newest sample: x = 4 - DELAY_S / TICK.
		assertEquals(4 - HeroTimeline.DELAY_S / TICK, t.poseAt(now).x(), 1e-9);
		// Half a tick later the pose has moved half a block: smooth, not stepped.
		assertEquals(4 - HeroTimeline.DELAY_S / TICK + 0.5, t.poseAt(now + TICK / 2).x(), 1e-9);
	}

	@Test
	void yawTurnsTheShortWayRound() {
		HeroTimeline t = new HeroTimeline();
		t.add(at(0, 0, 170), 100);
		t.add(at(1, 0, -170), 100 + TICK);
		t.add(at(2, 0, -170), 100 + 2 * TICK);
		// Halfway between the first two samples: 180, not 0.
		float yaw = t.poseAt(100 + TICK / 2 + HeroTimeline.DELAY_S).yaw();
		assertEquals(180, Math.abs(yaw), 1e-3);
	}

	@Test
	void aRecentreShiftsTheBufferSoPlaybackStaysContinuous() {
		HeroTimeline t = new HeroTimeline();
		t.add(at(0, 10, 0), 100);
		t.add(at(1, 11, 0), 100 + TICK);
		t.shift(-50, 0, 0);
		t.add(at(2, 12 - 50, 0), 100 + 2 * TICK);
		double mid = 100 + 1.5 * TICK + HeroTimeline.DELAY_S;
		assertEquals(11.5 - 50, t.poseAt(mid).x(), 1e-9);
	}

	/** Round 6 (2026-10-20): Deadlock's CurTime stopped advancing, and every frame ran past the newest sample. */
	@Test
	void playsFromTheTickCountWhenTheServerClockIsFrozen() {
		HeroTimeline frozen = new HeroTimeline(), ticks = new HeroTimeline();
		frozen.setAdaptive();
		ticks.setAdaptive();
		java.util.Random rng = new java.util.Random(1);
		double local = 100;
		for (long tick = 1000; tick < 1064; tick++) {
			local += TICK + (rng.nextDouble() - 0.5) * 0.004;  // arrival jitter
			frozen.add(new HeroTimeline.Sample(tick, 2733.0, tick, 0, 0, 0, 0, 64, 0, 0), local);
			ticks.add(new HeroTimeline.Sample(tick, HeroTimeline.serverTime(tick), tick, 0, 0, 0, 0, 64, 0, 0), local);
			// Five frames per tick (about 320 fps).
			for (int f = 0; f < 5; f++) {
				frozen.poseAt(local + f * TICK / 5);
				ticks.poseAt(local + f * TICK / 5);
			}
		}
		int[] stuck = frozen.takeDryFrames(), smooth = ticks.takeDryFrames();
		assertTrue(stuck[0] > stuck[1] * 0.9, "the frozen clock extrapolates nearly every frame (the bug): " + stuck[0] + " of " + stuck[1]);
		assertEquals(0, smooth[0], "the tick clock interpolates every frame");
	}
}
