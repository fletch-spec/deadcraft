package dev.deadcraft.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
